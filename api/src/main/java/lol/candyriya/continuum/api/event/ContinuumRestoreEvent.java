package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.report.RestoreReport;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired after a restore operation has finished — at join time, on rollback, or on an
 * admin-forced restore. Fired exactly once per restore, with the full report attached,
 * regardless of outcome: inspect {@code report.result()} for SUCCESS/PARTIAL/FAILED.
 *
 * <p><b>Thread:</b> fired on the thread that completes the restore stage — typically
 * Continuum's infrastructure executor, <em>not</em> a platform scheduler thread. Treat it
 * as an arbitrary thread; hop to the player's scheduler before touching game state.
 * <b>Cancellable:</b> no — the restore has already happened. <b>Mutability:</b> read-only.</p>
 *
 * @param playerId the player that was restored, never null
 * @param report the full per-module outcome, never null
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumRestoreEvent(
        @NotNull UUID playerId,
        @NotNull RestoreReport report) implements ContinuumEvent {

    /** Canonical constructor with validation. */
    public ContinuumRestoreEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(report, "report");
    }
}
