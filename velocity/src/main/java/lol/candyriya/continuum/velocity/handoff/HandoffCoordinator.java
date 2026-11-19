package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.i18n.TypedArgument;
import lol.candyriya.continuum.common.session.TransferState;
import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffReadyMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffResumedMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageException;
import lol.candyriya.continuum.messaging.pluginmessage.RouteOutcome;
import lol.candyriya.continuum.messaging.pluginmessage.RouteResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.SessionTokenMessage;
import lol.candyriya.continuum.velocity.Metrics;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.messaging.BackendMessenger;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The proxy side of the player transfer lifecycle.
 *
 * <p>One coordinator per proxy, thread-safe, everything storage-free. The
 * durable pieces of a transfer - the pre-transfer snapshot, the fencing token,
 * the restore - live on the backends and in the database; this class owns the
 * choreography between them and the honest accounting of what got as far as
 * what.</p>
 *
 * <h2>Message flow (as it actually flies)</h2>
 * <pre>
 * backend A                         proxy                            target B
 *    |  continuum.handoff.request     |                                |
 *    | ----------------------------&gt;  |  verify + RouteDecision         |
 *    |  continuum.route.result        |                                |
 *    | &lt;---------------------------  |                                |
 *    |         (player moves)         |  connect attempt                |
 *    |                                | -----------------------------&gt;  |
 *    |                                |  restore gate arms, loads      |
 *    |                                |  the snapshot, restores        |
 *    |                                |  continuum.handoff.ready        |
 *    |                                | &lt;-----------------------------  |
 *    |                                |  continuum.session.token        |
 *    |                                | -----------------------------&gt;  |
 *    |                                |  continuum.handoff.resumed      |
 *    |                                | &lt;-----------------------------  |
 * </pre>
 *
 * <p>The proxy never asks a backend to capture: there is no wire kind for it,
 * so a durable handoff can only start from the backend (the request frame is
 * proof the capture reached the database). {@link #initiate} exists for
 * switch requests and is honest about what it can and cannot promise.</p>
 *
 * <h2>Reconciliation</h2>
 * <ul>
 *     <li>No {@code handoff.resumed} within the configured restore timeout after
 *         the player lands - the player is disconnected with a restore-failure
 *         notice instead of left gate-blocked forever, and the transfer is marked
 *         {@link TransferState#RECOVERY_REQUIRED}.</li>
 *     <li>Pre-restore phases go stale after a few multiples of the same timeout;
 *         the record fails out instead of accumulating.</li>
 *     <li>Reconnect storms hit exactly one in-flight refusal per player.</li>
 * </ul>
 */
public final class HandoffCoordinator implements AutoCloseable {

    /** Ticket size for the handoff token handed to the target backend, in bytes. */
    private static final int TICKET_BYTES = 24;

    /** Pre-restore records older than this multiple of the timeout fail out. */
    private static final int STALENESS_MULTIPLE = 3;

    private final Logger logger;
    private final Metrics metrics;
    private final BackendMessenger messenger;
    private final VelocityMessages messages;
    private final Supplier<? extends Collection<RegisteredServer>> servers;
    private final LongSupplier restoreTimeoutSeconds;
    private final BooleanSupplier requireSnapshot;
    private final Function<UUID, Optional<Player>> playerLookup;
    private final RouteDecision routeDecision;
    private final PluginMessageCodec codec = new PluginMessageCodec();
    private final SecureRandom random = new SecureRandom();

    private final ConcurrentHashMap<UUID, PendingHandoff> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> restoreDeadlines = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    private volatile boolean closed;

    public HandoffCoordinator(Logger logger,
                              Metrics metrics,
                              BackendMessenger messenger,
                              VelocityMessages messages,
                              Supplier<? extends Collection<RegisteredServer>> servers,
                              LongSupplier restoreTimeoutSeconds,
                              BooleanSupplier requireSnapshot,
                              Function<UUID, Optional<Player>> playerLookup) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.messenger = Objects.requireNonNull(messenger, "messenger");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.restoreTimeoutSeconds = Objects.requireNonNull(restoreTimeoutSeconds, "restoreTimeoutSeconds");
        this.requireSnapshot = Objects.requireNonNull(requireSnapshot, "requireSnapshot");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
        this.routeDecision = new RouteDecision(servers);

        // daemon threads only; the sweeper must never keep a dying proxy alive
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "continuum-handoff-deadline");
            thread.setDaemon(true);
            return thread;
        };
        this.sweeper = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** Diagnostic view of an in-flight transfer; empty once it reaches a terminal state. */
    public Optional<PendingHandoff> pendingHandoff(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(pending.get(playerId));
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * Asks the proxy to switch a player to another backend.
     *
     * <p>Two flavors: with the shared {@code sync.transfer.require-snapshot} on
     * (the default), the proxy refuses - it cannot produce a durable
     * pre-transfer snapshot on its own and a switch that might lose data is worse
     * than no switch. The durable path starts from the backend via
     * {@code continuum.handoff.request}. With the flag off, the switch proceeds
     * unconfirmed and the target restores from the last good snapshot.</p>
     */
    public InitiateResult initiate(Player player, String targetBackend) {
        Objects.requireNonNull(player, "player");
        sweepStale();
        if (closed) {
            return InitiateResult.refused("handoff orchestration is shutting down");
        }
        String currentBackend = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (requireSnapshot.getAsBoolean()) {
            return InitiateResult.refused(
                    "this proxy cannot issue a durable pre-transfer snapshot; the backend has to drive the transfer");
        }
        RouteDecision.Decision decision = routeDecision.decide(targetBackend, currentBackend);
        if (!decision.allowed()) {
            metrics.increment("continuum.handoff.denied");
            return InitiateResult.refused(decision.reason());
        }
        Optional<RegisteredServer> target = findServer(decision.targetServer());
        if (target.isEmpty()) {
            // decided allowed but vanished from the enumeration underneath us; fail closed
            metrics.increment("continuum.handoff.failed");
            return InitiateResult.refused("server '" + decision.targetServer() + "' disappeared while routing");
        }
        PendingHandoff record = openRecord(player, decision.targetServer(), "switch");
        if (record == null) {
            metrics.increment("continuum.handoff.denied");
            return InitiateResult.refused("a transfer for this player is already in flight");
        }
        startConnect(player, target.get(), record);
        return InitiateResult.initiated(target.get().getServerInfo().getName());
    }

    /**
     * Feeds a raw backend frame to the handshake. Returns nothing; everything
     * visible happens through metrics, logs and the wire replies.
     */
    public void handleBackendFrame(ServerConnection source, byte[] payload) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(payload, "payload");
        sweepStale();
        if (closed) {
            logger.debug("ignoring a backend frame: the coordinator is closed");
            return;
        }
        DecodedPluginMessage decoded;
        try {
            decoded = codec.decode(payload, PluginMessageDirection.BACKEND_TO_PROXY);
        } catch (PluginMessageException e) {
            metrics.increment("continuum.handoff.bad_frame");
            logger.warn("dropping an invalid frame from backend '{}': {}", backendOf(source), e.getMessage());
            return;
        }
        PluginMessage message = decoded.message();
        Player sourcePlayer = source.getPlayer();
        if (!(message instanceof HandoffRequestMessage)
                && !(message instanceof HandoffReadyMessage)
                && !(message instanceof HandoffResumedMessage)) {
            // other kinds flow through the router for health/diagnostics; not ours to act on
            return;
        }
        UUID framePlayer = switch (message) {
            case HandoffRequestMessage m -> m.player();
            case HandoffReadyMessage m -> m.player();
            case HandoffResumedMessage m -> m.player();
            default -> throw new IllegalStateException("unreachable");
        };
        // the payload uuid is advisory by contract; the connection is the source of truth
        if (sourcePlayer == null || !sourcePlayer.getUniqueId().equals(framePlayer)) {
            metrics.increment("continuum.handoff.bad_frame");
            logger.warn("dropping a handoff frame whose player uuid does not match its connection (backend '{}')",
                    backendOf(source));
            return;
        }
        switch (message) {
            case HandoffRequestMessage request -> onHandoffRequest(source, request, decoded.correlationId());
            case HandoffReadyMessage ready -> onHandoffReady(source, ready, decoded.correlationId());
            case HandoffResumedMessage resumed -> onHandoffResumed(source, resumed);
            default -> throw new IllegalStateException("unreachable");
        }
    }

    /**
     * ServerPreConnectEvent policy for players with an in-flight transfer: the
     * connection we started is fine, anything sideways is not. Returns true when
     * the move should be denied. Vanilla switches (no record) are never denied.
     */
    public boolean denySidewaysMove(Player player, RegisteredServer originalServer) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(originalServer, "originalServer");
        PendingHandoff record = pending.get(player.getUniqueId());
        if (record == null) {
            return false;
        }
        String requested = originalServer.getServerInfo().getName();
        if (record.phase() == TransferState.CONNECTING && requested.equals(record.targetBackend())) {
            return false;
        }
        logger.warn("refusing the connection of {} to '{}' while their transfer to '{}' is {} ({})",
                player.getUniqueId(), requested, record.targetBackend(), record.phase(), record.reason());
        metrics.increment("continuum.handoff.sideways_denied");
        return true;
    }

    /** ServerConnectedEvent hook: lands the player in {@link TransferState#RESTORING} and arms the deadline. */
    public void onServerConnected(Player player, RegisteredServer server) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(server, "server");
        UUID id = player.getUniqueId();
        String name = server.getServerInfo().getName();
        PendingHandoff record = pending.get(id);
        if (record == null) {
            return;
        }
        if (!record.targetBackend().equals(name)) {
            logger.warn("player {} connected to '{}' while a transfer to '{}' was in flight; failing the record",
                    id, name, record.targetBackend());
            failRecord(record, TransferState.FAILED,
                    "player landed on '" + name + "' instead of '" + record.targetBackend() + "'");
            return;
        }
        PendingHandoff landed = advanceToRestoring(record);
        if (landed == null || record.phase() == TransferState.RESTORING) {
            return;
        }
        armRestoreDeadline(id, restoreTimeoutSeconds());
        metrics.increment("continuum.handoff.connected");
        logger.debug("player {} landed on '{}' (handoff); the restore gate is in charge there", id, name);
    }

    /** KickedFromServerEvent hook: a bounce is cheap before restore and expensive after. */
    public void onKickedFromServer(Player player, RegisteredServer server) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(server, "server");
        UUID id = player.getUniqueId();
        String name = server.getServerInfo().getName();
        PendingHandoff record = pending.get(id);
        if (record == null) {
            return;
        }
        if (record.phase() == TransferState.RESTORING && record.targetBackend().equals(name)) {
            logger.error("player {} was kicked from the target '{}' while the restore was (possibly) still running; "
                            + "marking the transfer recovery-required",
                    id, name);
            failRecord(record, TransferState.RECOVERY_REQUIRED, "kicked from the target during restore");
            return;
        }
        logger.warn("player {} was kicked from '{}' mid-handoff; failing the transfer", id, name);
        failRecord(record, "kicked from '" + name + "' mid-handoff");
    }

    /** DisconnectEvent hook: the player left the proxy; the transfer is over, one way or the other. */
    public void onDisconnect(Player player) {
        Objects.requireNonNull(player, "player");
        UUID id = player.getUniqueId();
        PendingHandoff record = pending.remove(id);
        cancelRestoreDeadline(id);
        if (record == null) {
            return;
        }
        if (!record.terminal()) {
            logger.warn("player {} left the proxy mid-handoff to '{}' at phase {}; failing the transfer",
                    id, record.targetBackend(), record.phase());
            metrics.increment("continuum.handoff.failed");
        }
    }

    private void onHandoffRequest(ServerConnection source, HandoffRequestMessage request, UUID correlationId) {
        UUID playerId = request.player();
        Player player = source.getPlayer();
        if (player == null) {
            // verified earlier in practice; keep the belt-and-braces answer honest anyway
            logger.warn("handoff request for {} arrived on a connection with no player; refused", playerId);
            metrics.increment("continuum.handoff.denied");
            sendRouteResult(source, request, correlationId, RouteOutcome.DENIED, "", "player is not on this connection");
            return;
        }
        String sourceBackend = backendOf(source);
        RouteDecision.Decision decision = routeDecision.decide(request.targetServer(), sourceBackend);
        if (!decision.allowed()) {
            metrics.increment("continuum.handoff.denied");
            sendRouteResult(source, request, correlationId, wireOutcome(decision.outcome()), "", decision.reason());
            logger.info("handoff of {} from '{}' to '{}' refused: {}", playerId, sourceBackend,
                    request.targetServer(), decision.reason());
            return;
        }
        PendingHandoff record = openRecord(player, decision.targetServer(), orEmpty(request.reason()));
        if (record == null) {
            metrics.increment("continuum.handoff.denied");
            sendRouteResult(source, request, correlationId, RouteOutcome.DENIED, "",
                    "a transfer for this player is already in flight");
            return;
        }
        // the backend asking is the proxy's proof that the pre-transfer snapshot is durable;
        // the target resolves right now or the whole thing collapses cleanly at this step
        Optional<RegisteredServer> target = findServer(decision.targetServer());
        if (target.isEmpty()) {
            failRecord(record, "target '" + decision.targetServer() + "' disappeared while routing");
            sendRouteResult(source, request, correlationId, RouteOutcome.TARGET_UNAVAILABLE, "",
                    "server '" + decision.targetServer() + "' is not available");
            return;
        }
        PendingHandoff requested = record.transitionTo(TransferState.HANDOFF_REQUESTED);
        pending.put(playerId, requested);
        metrics.increment("continuum.handoff.requested");
        sendRouteResult(source, request, correlationId, RouteOutcome.ALLOWED, decision.targetServer(), "");
        logger.info("handoff of {} from '{}' to '{}' approved; switching the player over",
                playerId, sourceBackend, decision.targetServer());
        startConnect(player, target.get(), requested);
    }

    private void onHandoffReady(ServerConnection source, HandoffReadyMessage ready, UUID correlationId) {
        UUID playerId = ready.player();
        PendingHandoff record = pending.get(playerId);
        if (record == null) {
            // nothing pending for this player: warmup we never asked for, or a late reply
            metrics.increment("continuum.handoff.stray_ready");
            logger.debug("ignoring handoff.ready for {} from '{}': no transfer in flight", playerId, backendOf(source));
            return;
        }
        String reportSource = backendOf(source);
        if (!reportSource.equals(record.targetBackend()) || !ready.server().equals(record.targetBackend())) {
            metrics.increment("continuum.handoff.ready_mismatch");
            logger.warn("ignoring handoff.ready for {} reporting server '{}'/'{}' while the transfer targets '{}'",
                    playerId, ready.server(), reportSource, record.targetBackend());
            return;
        }
        if (ready.ready()) {
            String ticket = mintTicket();
            long timeoutSeconds = restoreTimeoutSeconds();
            SessionTokenMessage token = new SessionTokenMessage(playerId, ticket,
                    Instant.now().plusSeconds(timeoutSeconds));
            sendTo(source, token, correlationId);
            metrics.increment("continuum.handoff.token_issued");
            logger.debug("issued the handoff token to '{}' for {}", record.targetBackend(), playerId);
            return;
        }
        logger.warn("target '{}' declined the handoff of {}: {}", record.targetBackend(), playerId, orEmpty(ready.detail()));
        abortAfterDecline(record, source);
    }

    private void onHandoffResumed(ServerConnection source, HandoffResumedMessage resumed) {
        UUID playerId = resumed.player();
        PendingHandoff record = pending.get(playerId);
        if (record == null) {
            metrics.increment("continuum.handoff.stray_resumed");
            logger.debug("ignoring handoff.resumed for {} from '{}': no transfer in flight", playerId, backendOf(source));
            return;
        }
        String reportSource = backendOf(source);
        if (!reportSource.equals(record.targetBackend()) || !resumed.server().equals(record.targetBackend())) {
            metrics.increment("continuum.handoff.resumed_mismatch");
            logger.warn("ignoring handoff.resumed for {} reporting server '{}'/'{}' while the transfer targets '{}'",
                    playerId, resumed.server(), reportSource, record.targetBackend());
            return;
        }
        PendingHandoff restoring = record;
        if (restoring.phase() != TransferState.RESTORING) {
            // RESUMED can overtake the connection bookkeeping; walk forward instead of rejecting it
            restoring = advanceToRestoring(record);
            if (restoring == null) {
                logger.warn("ignoring handoff.resumed for {} at an unexpected phase {}; the transfer stays as-is",
                        playerId, record.phase());
                metrics.increment("continuum.handoff.stray_resumed");
                return;
            }
        }
        pending.remove(playerId, restoring);
        cancelRestoreDeadline(playerId);
        final PendingHandoff committed = restoring;
        try {
            committed.transitionTo(TransferState.ACTIVE).transitionTo(TransferState.COMMITTED);
        } catch (IllegalHandoffTransitionException e) {
            // RESTORING admits ACTIVE; a race left a terminal record behind - not worth dying over
            logger.error("commit path hit an illegal transition for {}", playerId, e);
        }
        metrics.increment("continuum.handoff.committed");
        logger.info("transfer of {} to '{}' committed", playerId, committed.targetBackend());
        playerLookup.apply(playerId).ifPresent(player -> {
            try {
                messages.send(player, "transfer.completed", TypedArgument.text("server", committed.targetBackend()));
            } catch (RuntimeException e) {
                logger.debug("could not deliver the completion notice to {}: {}", playerId, e.toString());
            }
        });
    }

    private PendingHandoff openRecord(Player player, String target, String reason) {
        UUID id = player.getUniqueId();
        Instant now = Instant.now();
        PendingHandoff candidate = new PendingHandoff(id, player.getUsername(),
                player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null),
                target, orEmpty(reason), TransferState.CREATED, now, now);
        return pending.putIfAbsent(id, candidate) == null ? candidate : null;
    }

    private void startConnect(Player player, RegisteredServer target, PendingHandoff record) {
        UUID id = player.getUniqueId();
        PendingHandoff connecting;
        try {
            connecting = record.transitionTo(TransferState.CONNECTING);
        } catch (IllegalHandoffTransitionException e) {
            logger.error("could not advance the transfer of {} to CONNECTING", id, e);
            failRecord(record, "transition to CONNECTING was illegal");
            return;
        }
        pending.put(id, connecting);
        metrics.increment("continuum.handoff.connect_started");
        try {
            player.createConnectionRequest(target).connect().whenComplete((result, err) -> {
                if (err != null || result == null || !result.isSuccessful()) {
                    onConnectFailure(player, target.getServerInfo().getName(), err, result);
                } else {
                    logger.debug("connection of {} to '{}' accepted by the pipeline", id, target.getServerInfo().getName());
                }
            });
        } catch (RuntimeException e) {
            onConnectFailure(player, target.getServerInfo().getName(), e, null);
        }
    }

    private void onConnectFailure(Player player, String targetName, Throwable err, ConnectionRequestBuilder.Result result) {
        UUID id = player.getUniqueId();
        String detail = err != null ? err.getMessage()
                : result != null ? "request completed with status " + result.getStatus()
                : "no result";
        logger.warn("connection of {} to '{}' failed: {}", id, targetName, detail);
        PendingHandoff record = pending.get(id);
        if (record != null && record.phase() == TransferState.CONNECTING) {
            failRecord(record, "connect to '" + targetName + "' failed: " + detail);
            try {
                messages.send(player, "transfer.failed", TypedArgument.text("server", targetName));
            } catch (RuntimeException e) {
                // a player who vanished from the audience mid-connect is nothing new
                logger.debug("could not deliver the failure notice to {}: {}", id, e.toString());
            }
        }
    }

    private void abortAfterDecline(PendingHandoff record, ServerConnection source) {
        UUID id = record.playerId();
        if (record.phase() == TransferState.RESTORING) {
            // the player is physically on the target and the gate is holding them
            failRecord(record, TransferState.RECOVERY_REQUIRED, "target declined while the restore gate held the player");
            kickRestoreFailure(id);
            return;
        }
        failRecord(record, "target declined the handoff");
        Player player = source.getPlayer();
        if (player != null) {
            try {
                messages.send(player, "transfer.failed", TypedArgument.text("server", record.targetBackend()));
            } catch (RuntimeException e) {
                logger.debug("could not deliver the failure notice to {}: {}", id, e.toString());
            }
        }
    }

    private void failRecord(PendingHandoff record, String reason) {
        failRecord(record, TransferState.FAILED, reason);
    }

    private void failRecord(PendingHandoff record, TransferState terminal, String reason) {
        UUID id = record.playerId();
        pending.remove(id, record);
        cancelRestoreDeadline(id);
        PendingHandoff failed;
        try {
            failed = record.transitionTo(terminal);
        } catch (IllegalHandoffTransitionException e) {
            // already terminal elsewhere; nothing to fix, the bookkeeping stays consistent
            logger.debug("record for {} left the map at {}", id, record.phase());
            return;
        }
        if (failed.phase() == TransferState.RECOVERY_REQUIRED) {
            metrics.increment("continuum.handoff.recovery_required");
            logger.error("transfer of {} to '{}' is recovery-required: {}", id, failed.targetBackend(), reason);
        } else {
            metrics.increment("continuum.handoff.failed");
            logger.warn("transfer of {} to '{}' failed: {}", id, failed.targetBackend(), reason);
        }
    }

    private PendingHandoff advanceToRestoring(PendingHandoff record) {
        PendingHandoff current = record;
        try {
            if (current.phase() == TransferState.CREATED || current.phase() == TransferState.HANDOFF_REQUESTED) {
                current = current.transitionTo(TransferState.CONNECTING);
            }
            if (current.phase() == TransferState.CONNECTING) {
                current = current.transitionTo(TransferState.RESTORING);
            }
        } catch (IllegalHandoffTransitionException e) {
            logger.error("could not walk the transfer of {} to RESTORING from {}", record.playerId(), record.phase(), e);
            failRecord(current, "transition to RESTORING was illegal");
            return null;
        }
        if (current == record) {
            // already there; nothing to write back
            return current.phase() == TransferState.RESTORING ? current : null;
        }
        return pending.replace(record.playerId(), record, current) ? current : null;
    }

    private void armRestoreDeadline(UUID playerId, long timeoutSeconds) {
        ScheduledFuture<?> fresh;
        try {
            fresh = sweeper.schedule(() -> onRestoreDeadline(playerId), timeoutSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            logger.error("could not arm the restore deadline for {}; the restore timeout will not fire", playerId, e);
            return;
        }
        ScheduledFuture<?> previous = restoreDeadlines.put(playerId, fresh);
        cancelQuietly(previous);
    }

    private void cancelRestoreDeadline(UUID playerId) {
        cancelQuietly(restoreDeadlines.remove(playerId));
    }

    private void onRestoreDeadline(UUID playerId) {
        restoreDeadlines.remove(playerId);
        try {
            PendingHandoff record = pending.get(playerId);
            if (record == null || record.phase() != TransferState.RESTORING) {
                return;
            }
            logger.error("restore of {} on '{}' did not report back within the timeout; disconnecting with a restore-failure notice",
                    playerId, record.targetBackend());
            metrics.increment("continuum.handoff.timeout");
            failRecord(record, TransferState.RECOVERY_REQUIRED,
                    "no handoff.resumed within the restore timeout");
            kickRestoreFailure(playerId);
        } catch (Throwable t) {
            // a deadline must never take the sweeper down for everyone else
            logger.error("restore deadline handling blew up for {}", playerId, t);
        }
    }

    private void kickRestoreFailure(UUID playerId) {
        playerLookup.apply(playerId).ifPresent(player -> {
            try {
                player.disconnect(messages.render(messages.localeFor(player), "player.restore-failed"));
            } catch (RuntimeException e) {
                logger.error("could not disconnect {} with the restore-failure notice", playerId, e);
            }
        });
    }

    private void sendRouteResult(ServerConnection source, HandoffRequestMessage request, UUID correlationId,
                                 RouteOutcome outcome, String targetServer, String reason) {
        try {
            RouteResultMessage reply = new RouteResultMessage(request.player(), outcome, targetServer, reason);
            sendTo(source, reply, correlationId);
        } catch (RuntimeException e) {
            logger.error("could not send the route result to backend '{}'", backendOf(source), e);
        }
    }

    private void sendTo(ServerConnection target, PluginMessage message, UUID correlationId) {
        byte[] frame = codec.encode(message, correlationId);
        boolean queued = messenger.send(target, frame);
        if (!queued) {
            metrics.increment("continuum.handoff.send_failed");
        }
    }

    private String mintTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private Optional<RegisteredServer> findServer(String name) {
        try {
            List<RegisteredServer> known = List.copyOf(servers.get());
            return known.stream()
                    .filter(server -> server.getServerInfo().getName().equals(name))
                    .findFirst();
        } catch (RuntimeException e) {
            logger.error("failed to enumerate configured servers while resolving '{}'", name, e);
            return Optional.empty();
        }
    }

    private void sweepStale() {
        long cutoffSeconds = Instant.now().minusSeconds(STALENESS_MULTIPLE * restoreTimeoutSeconds()).getEpochSecond();
        pending.forEach((id, record) -> {
            if (record.terminal()) {
                pending.remove(id, record);
                return;
            }
            if (record.phase() != TransferState.RESTORING && record.createdAt().getEpochSecond() < cutoffSeconds) {
                metrics.increment("continuum.handoff.timeout");
                failRecord(record, "transfer was stale in phase " + record.phase() + " and was failed out");
            }
        });
    }

    private long restoreTimeoutSeconds() {
        long seconds = restoreTimeoutSeconds.getAsLong();
        return seconds >= 1 ? seconds : 1;
    }

    private static RouteOutcome wireOutcome(RouteDecision.Outcome outcome) {
        return switch (outcome) {
            case ALLOWED -> RouteOutcome.ALLOWED;
            case DENIED -> RouteOutcome.DENIED;
            case TARGET_UNAVAILABLE -> RouteOutcome.TARGET_UNAVAILABLE;
        };
    }

    private static String backendOf(ServerConnection connection) {
        return connection.getServerInfo().getName();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        restoreDeadlines.values().forEach(HandoffCoordinator::cancelQuietly);
        restoreDeadlines.clear();
        pending.clear();
        sweeper.shutdownNow();
    }
}
