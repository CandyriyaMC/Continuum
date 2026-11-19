package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.messaging.pluginmessage.CaptureRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageException;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageKind;
import lol.candyriya.continuum.velocity.Metrics;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.messaging.BackendMessenger;
import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The capture seam for vanilla {@code /server} switches.
 *
 * <p>When a player is about to be moved to another backend, the proxy cannot
 * trust quit-capture to land in time — the restore on the target may win the
 * race against the final write on the source. So before the switch is
 * allowed, this listener asks the SOURCE backend to capture the player NOW
 * ({@code continuum.capture.request}) and waits for the answer
 * ({@code continuum.capture.result}). Captured: the switch proceeds and the
 * handoff machinery is none the wiser. Anything else — refused capture,
 * timeout, send failure, a blow-up inside this listener — and the switch is
 * refused with an operator-facing notice; the player stays put and their
 * data is safe. Fail closed, always.</p>
 *
 * <p>Ordering: this listener runs at {@value #EVENT_PRIORITY} on
 * {@link ServerPreConnectEvent} so the deny lands before the handoff
 * bookkeeping (default priority 0) evaluates the same event. It never sets
 * an allow — silence here means "not ours to stop" and the rest of the
 * pipeline decides.</p>
 *
 * <p>Not every switch is intercepted:</p>
 * <ul>
 *     <li>plugin messaging disabled in config — the handoff coordinator is
 *         the only transfer path then, and double-denying vanilla switches
 *         would be wrong;</li>
 *     <li>the player is not on any backend, or the target equals the current
 *         backend — nothing to leave behind / forced-host loops;</li>
 *     <li>a coordinated transfer is in flight for this player — the backend
 *         asked the proxy to move them and already proved its capture
 *         durable; the handoff flow is in charge;</li>
 *     <li>a capture fired within the last {@value #DEBOUNCE_MILLIS} ms for
 *         this player — a fresh one is redundant (reconnect-storm guard).</li>
 * </ul>
 *
 * <p>The reply is matched purely by the 16-byte correlation id the request
 * carried; unknown replies are logged and dropped. Debounce state is a
 * timestamp per player, swept opportunistically — reconnect storms cannot
 * grow it without bound.</p>
 */
public final class CaptureOnSwitchListener {

    /** Runs before the handoff bookkeeping (default priority 0) so a deny here stands. */
    static final short EVENT_PRIORITY = 100;

    /** Same player, watched twice within this window: no second capture. */
    static final long DEBOUNCE_MILLIS = 2_000;

    /** Replies carry no player uuid; pairing is correlation-id only. */
    private final ConcurrentHashMap<UUID, CompletableFuture<CaptureResultMessage>> pending = new ConcurrentHashMap<>();
    /** player uuid → epoch millis of the last capture request fired (attempts, not just successes). */
    private final ConcurrentHashMap<UUID, Long> lastCaptureAt = new ConcurrentHashMap<>();

    private final HandoffCoordinator coordinator;
    private final BackendMessenger messenger;
    private final VelocityMessages messages;
    private final Metrics metrics;
    private final Logger logger;
    private final BooleanSupplier messagingEnabled;
    private final LongSupplier captureTimeoutSeconds;
    private final PluginMessageCodec codec = new PluginMessageCodec();

    public CaptureOnSwitchListener(HandoffCoordinator coordinator,
                                   BackendMessenger messenger,
                                   VelocityMessages messages,
                                   Metrics metrics,
                                   Logger logger,
                                   BooleanSupplier messagingEnabled,
                                   LongSupplier captureTimeoutSeconds) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.messagingEnabled = Objects.requireNonNull(messagingEnabled, "messagingEnabled");
        this.captureTimeoutSeconds = Objects.requireNonNull(captureTimeoutSeconds, "captureTimeoutSeconds");
    }

    /** Pure decision function — everything the interceptor needs to know before touching the wire. */
    static InterceptDecision shouldIntercept(boolean messagingEnabled,
                                             boolean transferInFlight,
                                             String currentBackend,
                                             String targetBackend,
                                             long nowMillis,
                                             Long lastCaptureMillis) {
        if (!messagingEnabled) {
            return InterceptDecision.skip("messaging-disabled");
        }
        if (currentBackend == null) {
            return InterceptDecision.skip("not-on-a-backend");
        }
        if (targetBackend == null || targetBackend.isEmpty()) {
            return InterceptDecision.skip("no-target");
        }
        if (currentBackend.equals(targetBackend)) {
            return InterceptDecision.skip("same-server");
        }
        if (transferInFlight) {
            return InterceptDecision.skip("transfer-in-flight");
        }
        if (lastCaptureMillis != null && nowMillis - lastCaptureMillis < DEBOUNCE_MILLIS) {
            return InterceptDecision.skip("debounce");
        }
        return InterceptDecision.interceptNow();
    }

    @Subscribe(priority = EVENT_PRIORITY)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        try {
            intercept(event);
        } catch (RuntimeException e) {
            // routing hiccup must never leave the pipeline wedged — but it also may not let the switch through
            logger.error("capture-on-switch blew up for {}; refusing the switch", event.getPlayer().getUniqueId(), e);
            deny(event, event.getPlayer(),
                    event.getOriginalServer().getServerInfo().getName(), "capture failed with an internal error");
        }
    }

    private void intercept(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String target = event.getOriginalServer().getServerInfo().getName();
        Optional<ServerConnection> current = player.getCurrentServer();
        String currentBackend = current.map(connection -> connection.getServerInfo().getName()).orElse(null);

        InterceptDecision decision = shouldIntercept(messagingEnabled.getAsBoolean(),
                coordinator.pendingHandoff(player.getUniqueId()).isPresent(),
                currentBackend, target, System.currentTimeMillis(), lastCaptureAt.get(player.getUniqueId()));
        if (!decision.intercept()) {
            logger.debug("capture-on-switch skipped for {} → '{}': {}", player.getUniqueId(), target, decision.reason());
            return;
        }

        ServerConnection connection = current.orElseThrow(); // decide() only says intercept with a backend in tow
        UUID correlationId = UUID.randomUUID();
        CompletableFuture<CaptureResultMessage> future = new CompletableFuture<>();
        pending.put(correlationId, future);
        try {
            interceptWithPending(event, player, target, connection, correlationId, future);
        } catch (RuntimeException e) {
            // no entry may outlive the decision that used it
            pending.remove(correlationId, future);
            throw e;
        }
    }

    private void interceptWithPending(ServerPreConnectEvent event, Player player, String target,
                                      ServerConnection connection, UUID correlationId,
                                      CompletableFuture<CaptureResultMessage> future) {
        String currentBackend = connection.getServerInfo().getName();
        boolean queued;
        try {
            byte[] frame = codec.encode(new CaptureRequestMessage(player.getUniqueId(), "switch"), correlationId);
            queued = messenger.send(connection, frame);
        } catch (RuntimeException e) {
            queued = false;
            logger.error("could not send the capture request for {} to '{}'", player.getUniqueId(), currentBackend, e);
        }
        if (!queued) {
            pending.remove(correlationId, future);
            deny(event, player, target, "the backend could not be asked to capture");
            return;
        }
        lastCaptureAt.put(player.getUniqueId(), System.currentTimeMillis());
        sweepDebounce();

        CaptureResultMessage result = awaitResult(future, correlationId);
        if (result != null && result.captured()) {
            metrics.increment("continuum.capture.allowed");
            logger.debug("capture-on-switch: {} captured before moving to '{}'", player.getUniqueId(), target);
            return;
        }
        String reason = result == null ? "no answer within the capture timeout"
                : result.detail() != null ? result.detail() : "the backend declined the capture";
        deny(event, player, target, reason);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        try {
            if (!ContinuumChannels.MAIN.equals(event.getIdentifier())) {
                return;
            }
            if (!(event.getSource() instanceof ServerConnection source)) {
                // client-sourced frames on our channel are not backend voices
                return;
            }
            byte[] payload = event.getData();
            // cheap pre-filter: magic(4) + version(1), then the kind byte; the full decode happens below
            if (payload == null || payload.length < 6 || (payload[5] & 0xff) != PluginMessageKind.CAPTURE_RESULT.id()) {
                return;
            }
            DecodedPluginMessage decoded;
            try {
                decoded = codec.decode(payload, PluginMessageDirection.BACKEND_TO_PROXY);
            } catch (PluginMessageException e) {
                metrics.increment("continuum.capture.bad_frame");
                logger.warn("dropping a malformed capture result from backend '{}': {}",
                        source.getServerInfo().getName(), e.getMessage());
                return;
            }
            if (!(decoded.message() instanceof CaptureResultMessage result)) {
                return; // unreachable behind the pre-filter; belt and braces
            }
            CompletableFuture<CaptureResultMessage> future = pending.remove(decoded.correlationId());
            if (future == null) {
                logger.debug("capture result from '{}' for an unknown or finished request; stray frame",
                        source.getServerInfo().getName());
                return;
            }
            future.complete(result);
        } catch (RuntimeException e) {
            logger.error("capture result handling blew up", e);
        }
    }

    private CaptureResultMessage awaitResult(CompletableFuture<CaptureResultMessage> future, UUID correlationId) {
        long seconds = Math.max(1, captureTimeoutSeconds.getAsLong());
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            metrics.increment("continuum.capture.timeout");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            logger.warn("capture request failed while waiting", e.getCause());
            return null;
        } finally {
            pending.remove(correlationId, future);
        }
    }

    private void deny(ServerPreConnectEvent event, Player player, String target, String reason) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        metrics.increment("continuum.capture.denied");
        logger.warn("capture-on-switch denied for {} → '{}': {}; the player stays put", player.getUniqueId(), target, reason);
        try {
            messages.send(player, "velocity.capture-failed");
        } catch (RuntimeException e) {
            logger.warn("could not deliver the capture-failed notice to {}", player.getUniqueId(), e);
        }
    }

    private void sweepDebounce() {
        if (lastCaptureAt.size() <= 256) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 60_000;
        lastCaptureAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /** The outcome of the pre-wire decision; {@code intercept()==false} means hands off. */
    record InterceptDecision(boolean intercept, String reason) {

        static InterceptDecision interceptNow() {
            return new InterceptDecision(true, "");
        }

        static InterceptDecision skip(String reason) {
            return new InterceptDecision(false, reason);
        }
    }
}
