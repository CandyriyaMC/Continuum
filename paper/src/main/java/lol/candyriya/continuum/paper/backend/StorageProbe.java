package lol.candyriya.continuum.paper.backend;

/**
 * Probes the storage layer's health. Called on the async pre-login thread and
 * on the entity thread — implementations must be cheap and thread-safe (read
 * cached state, never touch I/O inline).
 *
 * <p>Throwing is treated as {@link StorageStatus#DEGRADED}: a probe that cannot
 * answer is not evidence of health.</p>
 */
@FunctionalInterface
public interface StorageProbe {

    /** Current storage status. Must not block meaningfully. */
    StorageStatus status();
}
