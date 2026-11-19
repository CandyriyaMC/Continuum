package lol.candyriya.continuum.velocity.messaging;

import lol.candyriya.continuum.velocity.Metrics;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Outbound half of the plugin messaging pipe: frames from the proxy to a backend.
 *
 * <p>The size cap comes from the shared config
 * ({@code messaging.plugin-message.max-payload-bytes}) and is read on every
 * send, so the reload command applies it without a restart. Oversized frames
 * are refused before they reach the netty layer - a single fat frame should
 * never buy enough memory to hurt the proxy.</p>
 *
 * <p>Channel messages are best-effort by design: a backend with no connected
 * players receives nothing, and there is no redelivery. Durable semantics live
 * in the storage/outbox side; this pipe is for presence and hints.</p>
 */
public final class BackendMessenger {

    private final Logger logger;
    private final Metrics metrics;
    private final IntSupplier maxPayloadBytes;

    public BackendMessenger(Logger logger, Metrics metrics, IntSupplier maxPayloadBytes) {
        this.logger = logger;
        this.metrics = metrics;
        this.maxPayloadBytes = maxPayloadBytes;
    }

    public ChannelIdentifier channel() {
        return ContinuumChannels.MAIN;
    }

    /** Current configured frame cap in bytes. */
    public int maxPayloadBytes() {
        return maxPayloadBytes.getAsInt();
    }

    /**
     * Sends a frame to the backend the connection is bound to.
     *
     * @param target  the connection to write to
     * @param payload the frame
     * @return true when the proxy queued the frame, false when it was refused (oversized)
     *         or the backend could not take it (no connected players)
     */
    public boolean send(ServerConnection target, byte[] payload) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");

        int max = maxPayloadBytes.getAsInt();
        String backend = target.getServerInfo().getName();
        if (payload.length > max) {
            logger.error("refusing to send frame to backend '{}': {} byte(s) exceed messaging.plugin-message.max-payload-bytes ({})",
                    backend, payload.length, max);
            metrics.increment("continuum.messaging.frames.rejected");
            return false;
        }
        boolean queued = target.sendPluginMessage(ContinuumChannels.MAIN, payload);
        if (!queued) {
            logger.warn("backend '{}' could not take a {}-byte frame (no connected players, or channel unknown there)",
                    backend, payload.length);
            metrics.increment("continuum.messaging.frames.failed");
        }
        return queued;
    }
}
