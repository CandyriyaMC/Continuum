package lol.candyriya.continuum.velocity.messaging;

import lol.candyriya.continuum.velocity.Metrics;

import com.velocitypowered.api.proxy.ServerConnection;

import org.slf4j.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default router: tracks traffic, decodes nothing yet.
 *
 * <p>Envelope decoding lands together with the messaging layer. Keeping this
 * implementation real - counters, per-backend breakdown, last-frame timestamp -
 * means {@code /continuum status} can show live messaging health today, and the
 * codec drops in behind the same interface without any plumbing changes.</p>
 */
public final class DefaultPluginMessageRouter implements PluginMessageRouter {

    private final Logger logger;
    private final Metrics metrics;

    private final AtomicLong acceptedFrames = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> framesByBackend = new ConcurrentHashMap<>();
    private volatile Instant lastFrameAt;

    public DefaultPluginMessageRouter(Logger logger, Metrics metrics) {
        this.logger = logger;
        this.metrics = metrics;
    }

    @Override
    public void route(ServerConnection source, byte[] payload) {
        String backend = source.getServerInfo().getName();
        acceptedFrames.incrementAndGet();
        framesByBackend.computeIfAbsent(backend, key -> new AtomicLong()).incrementAndGet();
        lastFrameAt = Instant.now();
        metrics.increment("continuum.messaging.frames.accepted");
        logger.debug("accepted opaque {}-byte frame from backend '{}'; envelope decoding is not wired yet", payload.length, backend);
    }

    /** Current health snapshot for the status command. */
    public MessagingHealth health(boolean enabled, String channel, int maxPayloadBytes) {
        Map<String, Long> counts = new TreeMap<>();
        framesByBackend.forEach((backend, count) -> counts.put(backend, count.get()));
        return new MessagingHealth(enabled, channel, maxPayloadBytes,
                acceptedFrames.get(), counts, Optional.ofNullable(lastFrameAt));
    }
}
