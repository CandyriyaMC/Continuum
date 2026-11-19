package lol.candyriya.continuum.velocity.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of the plugin messaging path's health, as tracked by the proxy.
 *
 * @param enabled         whether the plugin-message transport is enabled in config
 * @param channel         the channel frames travel on
 * @param maxPayloadBytes the configured frame size cap
 * @param acceptedFrames  frames accepted from backends since boot (oversized/untrusted frames are not counted)
 * @param framesByBackend per-backend frame counters
 * @param lastFrameAt     when the last frame was accepted
 */
public record MessagingHealth(boolean enabled,
                              String channel,
                              int maxPayloadBytes,
                              long acceptedFrames,
                              Map<String, Long> framesByBackend,
                              Optional<Instant> lastFrameAt) {

    public MessagingHealth {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(framesByBackend, "framesByBackend");
        Objects.requireNonNull(lastFrameAt, "lastFrameAt");
        framesByBackend = Map.copyOf(framesByBackend);
    }
}
