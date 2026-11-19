package lol.candyriya.continuum.api.report;

import org.jetbrains.annotations.ApiStatus;

/**
 * Outcome of a single module within a capture or restore operation.
 *
 * <ul>
 *   <li>{@link #OK} — the module did its work; the payload is consistent.</li>
 *   <li>{@link #WARN} — the module worked but something deserves attention (a non-fatal
 *       truncation, a fallback value used, a suspicious-but-tolerated payload). The
 *       operation continues and the overall {@code SyncResult} is not degraded by the
 *       warning itself.</li>
 *   <li>{@link #FAILED} — the module failed. Whether the operation continues is governed
 *       by the module's {@code FailurePolicy}: optional modules degrade the result to
 *       {@code PARTIAL}, required modules abort it.</li>
 *   <li>{@link #SKIPPED} — the module did not run, either because it is disabled by
 *       configuration or because a dependency it needed was missing or failed.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public enum ModuleStatus {

    /** Success. */
    OK,

    /** Success, but with something worth reporting. */
    WARN,

    /** Failure; fatal or isolated depending on the module's failure policy. */
    FAILED,

    /** Not attempted. */
    SKIPPED
}
