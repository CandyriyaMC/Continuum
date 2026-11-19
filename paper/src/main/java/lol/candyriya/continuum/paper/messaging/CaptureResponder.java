package lol.candyriya.continuum.paper.messaging;

import lol.candyriya.continuum.messaging.pluginmessage.CaptureRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageException;
import lol.candyriya.continuum.paper.transfer.TransferCaptureOutcome;
import lol.candyriya.continuum.paper.transfer.TransferParticipant;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound handler for {@code continuum.capture.request} — the proxy is about
 * to move this player off this backend and asks for a durable pre-transfer
 * snapshot first.
 *
 * <p>The request arrives through the player's own connection (that is how the
 * proxy reaches a backend it shares no other channel with), so the backend
 * cannot cryptographically tell a proxy frame from a hostile client's frame.
 * The blast radius is bounded instead of authenticated: the responder only
 * ever captures the player whose connection carried the frame (a mismatch is
 * refused with {@code captured=false}), one honored capture per player per
 * second at most, and the reply always answers — never silence. Modern
 * forwarding remains the network-level gate for who may terminate the link
 * at all.</p>
 *
 * <p>The reply goes out on the SAME correlation id, carrying
 * {@code captured=persisted}, the persisted snapshot id when there is one,
 * and an operator-facing English detail when there is not. The proxy waits
 * for this frame with a hard timeout and refuses the switch when it does not
 * come, so a backend that cannot capture must answer {@code captured=false}
 * instead of going quiet.</p>
 */
public final class CaptureResponder implements PluginMessageListener {

    /** Refusal window against request spam — the proxy debounces too, this is the belt to its braces. */
    private static final long MIN_CAPTURE_INTERVAL_MILLIS = 1_000;

    private static final int SANITIZED_DETAIL_MAX = 256;

    private final Plugin plugin;
    private final TransferParticipant transfer;
    private final String channel;
    private final Logger logger;
    private final PluginMessageCodec codec = new PluginMessageCodec();
    private final ConcurrentHashMap<UUID, Long> lastHonoredAt = new ConcurrentHashMap<>();

    public CaptureResponder(Plugin plugin, TransferParticipant transfer, String channel, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        Objects.requireNonNull(channel, "channel");
        if (channel.isEmpty() || channel.indexOf(':') < 1) {
            throw new IllegalArgumentException("channel must be a namespaced key like continuum:main, got '" + channel + "'");
        }
        this.channel = channel;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        // bukkit only calls subscribed channels; the equality re-check keeps direct test calls honest
        if (!this.channel.equals(channel)) {
            return;
        }
        DecodedPluginMessage decoded;
        try {
            decoded = codec.decode(message, PluginMessageDirection.PROXY_TO_BACKEND);
        } catch (PluginMessageException e) {
            // without a clean decode there is no trustworthy correlation id to answer with — drop it
            logger.warn("dropping a malformed proxy frame on '{}': {}", channel, e.getMessage());
            return;
        }
        if (!(decoded.message() instanceof CaptureRequestMessage request)) {
            // another kind on our channel; not ours to service (the handoff handshake lives elsewhere)
            return;
        }
        UUID correlationId = decoded.correlationId();
        UUID connectionPlayer = player.getUniqueId();
        UUID target = request.targetUuid();
        if (target == null || !target.equals(connectionPlayer)) {
            logger.warn("refused a capture request for {} on the connection of {}", target, connectionPlayer);
            reply(player, correlationId, new CaptureResultMessage(false, null,
                    "the capture request does not match the connection's player"));
            return;
        }
        if (!rateLimitHonored(connectionPlayer)) {
            logger.warn("rate-limited a capture request for {}: one per second, no exceptions", connectionPlayer);
            reply(player, correlationId, new CaptureResultMessage(false, null,
                    "capture was already honored within the last second"));
            return;
        }
        transfer.prepareTransfer(player).whenComplete((outcome, err) -> {
            CaptureResultMessage result = toResult(outcome, err, connectionPlayer);
            if (result.captured()) {
                logger.info("captured {} for a pending switch (snapshotId={})", connectionPlayer, result.snapshotId());
            } else {
                logger.warn("capture for a pending switch of {} did not persist: {}", connectionPlayer, result.detail());
            }
            reply(player, correlationId, result);
        });
    }

    private boolean rateLimitHonored(UUID playerId) {
        long now = System.currentTimeMillis();
        boolean[] honored = {false};
        lastHonoredAt.compute(playerId, (id, previous) -> {
            if (previous != null && now - previous < MIN_CAPTURE_INTERVAL_MILLIS) {
                return previous;
            }
            honored[0] = true;
            return now;
        });
        // opportunistic sweep so the map stays bounded on a busy node
        if (lastHonoredAt.size() > 256) {
            lastHonoredAt.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);
        }
        return honored[0];
    }

    private CaptureResultMessage toResult(@Nullable TransferCaptureOutcome outcome, Throwable err, UUID playerId) {
        if (err != null) {
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            return new CaptureResultMessage(false, null, sanitize("capture did not complete: " + cause.getMessage()));
        }
        if (outcome == null) {
            logger.error("capture future for {} completed with no outcome and no error", playerId);
            return new CaptureResultMessage(false, null, "capture produced no outcome");
        }
        if (outcome.snapshotPersisted()) {
            return new CaptureResultMessage(true, outcome.snapshotId(), null);
        }
        String detail = outcome.detail() != null ? outcome.detail() : "the snapshot was not persisted";
        return new CaptureResultMessage(false, outcome.snapshotId(), sanitize(detail));
    }

    private void reply(Player player, UUID correlationId, CaptureResultMessage result) {
        final byte[] frame;
        try {
            frame = codec.encode(result, correlationId);
        } catch (PluginMessageException | IllegalArgumentException e) {
            // encode-time validation blew up; the proxy waits for a frame that is not coming — say so loudly
            logger.error("could not encode the capture result for {}", player.getUniqueId(), e);
            return;
        }
        try {
            // plugin messages ride the player connection; netty handles the cross-thread write
            player.sendPluginMessage(plugin, channel, frame);
        } catch (RuntimeException e) {
            logger.warn("could not deliver the capture result to the proxy for {}", player.getUniqueId(), e);
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "unknown capture failure";
        }
        String cleaned = raw.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned.isEmpty()) {
            return "unknown capture failure";
        }
        return cleaned.length() <= SANITIZED_DETAIL_MAX ? cleaned : cleaned.substring(0, SANITIZED_DETAIL_MAX);
    }
}
