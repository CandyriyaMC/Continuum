package lol.candyriya.continuum.paper.orchestration;

import java.time.Duration;

/**
 * Tuning for the backend sync pipeline that has no configuration surface today.
 *
 * <p>The knobs that do exist in the shared config are honored elsewhere:
 * {@code sync.autosave.*} drives the autosave sweep, {@code sync.modules.*}
 * gates individual modules, {@code sync.recovery.restore-order} (re)orders the
 * restore plan, and {@code gate.restore-timeout-seconds} bounds the join gate's
 * patience. Everything in this record is a backend-local constant with a sane
 * default; constructors accept overrides so tests can shrink them.</p>
 *
 * @param fifoCapacityPerPlayer maximum queued DB operations per player before new
 *                              submissions are refused (logged, never silently dropped);
 *                              protects the heap from an unbounded backlog
 * @param fifoWorkers           how many virtual-thread workers drain the per-player queues
 * @param idleEvictionAfter     how long a player queue may sit idle (empty and unused)
 *                              before it is evicted from the tracking map
 * @param moduleIoTimeout       how long to wait for one module's capture/restore stage
 *                              before reporting TIMEOUT; first-party modules are
 *                              synchronous, this is a guard rail, not a budget
 * @param shutdownCaptureBudget total wall-clock budget for the best-effort capture of all
 *                              online players during backend shutdown; the shutdown path
 *                              gives up after this and proceeds
 * @param restoreBudget         total wall-clock budget for one restore; modules still unaccounted
 *                              for when it expires are reported TIMEOUT and the restore
 *                              finalizes with what it has — the join gate's own
 *                              {@code gate.restore-timeout-seconds} remains the backstop
 *                              that kicks a player whose restore never lands
 */
public record OrchestrationSettings(int fifoCapacityPerPlayer,
                                    int fifoWorkers,
                                    Duration idleEvictionAfter,
                                    Duration moduleIoTimeout,
                                    Duration shutdownCaptureBudget,
                                    Duration restoreBudget) {

    /** Production defaults: 256 slots per player, 8 workers, 5 minute idle eviction, restore budget below the gate's 5s patience. */
    public static OrchestrationSettings defaults() {
        return new OrchestrationSettings(256, 8,
                Duration.ofMinutes(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(4));
    }

    public OrchestrationSettings {
        if (fifoCapacityPerPlayer < 1) {
            throw new IllegalArgumentException("fifoCapacityPerPlayer must be >= 1, got " + fifoCapacityPerPlayer);
        }
        if (fifoWorkers < 1) {
            throw new IllegalArgumentException("fifoWorkers must be >= 1, got " + fifoWorkers);
        }
        if (idleEvictionAfter == null || idleEvictionAfter.isNegative() || idleEvictionAfter.isZero()) {
            throw new IllegalArgumentException("idleEvictionAfter must be positive, got " + idleEvictionAfter);
        }
        if (moduleIoTimeout == null || moduleIoTimeout.isNegative() || moduleIoTimeout.isZero()) {
            throw new IllegalArgumentException("moduleIoTimeout must be positive, got " + moduleIoTimeout);
        }
        if (shutdownCaptureBudget == null || shutdownCaptureBudget.isNegative() || shutdownCaptureBudget.isZero()) {
            throw new IllegalArgumentException("shutdownCaptureBudget must be positive, got " + shutdownCaptureBudget);
        }
        if (restoreBudget == null || restoreBudget.isNegative() || restoreBudget.isZero()) {
            throw new IllegalArgumentException("restoreBudget must be positive, got " + restoreBudget);
        }
    }
}
