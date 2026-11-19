package lol.candyriya.continuum.messaging.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning for {@link OutboxRelay}.
 *
 * @param pollInterval how often the relay claims a batch; default 1s. The
 *                     relay is single-threaded per config — no overlap, a
 *                     slow batch just delays the next tick
 * @param claimBatchSize rows claimed per tick; default 100
 * @param closeAwait    how long {@code close()} waits for an in-flight poll
 *                      to finish before interrupting it; default 10s
 */
public record OutboxRelayConfig(Duration pollInterval, int claimBatchSize, Duration closeAwait) {

    public OutboxRelayConfig {
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        Objects.requireNonNull(closeAwait, "closeAwait must not be null");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive, got " + pollInterval);
        }
        if (claimBatchSize < 1) {
            throw new IllegalArgumentException("claimBatchSize must be >= 1, got " + claimBatchSize);
        }
        if (closeAwait.isNegative() || closeAwait.isZero()) {
            throw new IllegalArgumentException("closeAwait must be positive, got " + closeAwait);
        }
    }

    public static OutboxRelayConfig defaults() {
        return new OutboxRelayConfig(Duration.ofSeconds(1), 100, Duration.ofSeconds(10));
    }
}
