package lol.candyriya.continuum.storage.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Storage-layer tuning that is not connection-level (those live in the
 * datasource settings) and not serialization-level (those live in
 * {@code SerializationConfig}).
 *
 * @param historyKeepLast how many history rows to keep per player; the save
 *                        transaction prunes beyond this; {@code >= 1}
 * @param sessionLease    how long a session lease lasts without a heartbeat;
 *                        advisory only, fencing still guards writes; positive
 * @param leakDetection   when true, the pool flags connections held longer than
 *                        10 seconds; development knob, keep off in production
 */
public record StorageConfig(int historyKeepLast, Duration sessionLease, boolean leakDetection) {

    public StorageConfig {
        if (historyKeepLast < 1) {
            throw new IllegalArgumentException("historyKeepLast must be >= 1, got " + historyKeepLast);
        }
        Objects.requireNonNull(sessionLease, "sessionLease must not be null");
        if (sessionLease.isNegative() || sessionLease.isZero()) {
            throw new IllegalArgumentException("sessionLease must be positive, got " + sessionLease);
        }
    }

    /** Keep 10 history rows per player, 90 second lease, leak detection off. */
    public static StorageConfig defaults() {
        return new StorageConfig(10, Duration.ofSeconds(90), false);
    }
}
