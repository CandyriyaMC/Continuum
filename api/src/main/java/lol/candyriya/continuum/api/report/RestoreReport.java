package lol.candyriya.continuum.api.report;

import lol.candyriya.continuum.api.snapshot.SyncResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of applying a stored snapshot to a player — at join, on rollback, or on an
 * admin-forced restore.
 *
 * @param playerId the player that was restored, never null
 * @param snapshotId the snapshot that was applied, &ge; 1
 * @param result the overall verdict, never null; {@code FAILED} means the player's state
 *        is not what the snapshot contains and the caller must route the player into the
 *        recovery flow
 * @param modules the per-module rows in restore planning order, never null; copied — unmodifiable
 * @param completedAt when the last module applied, never null
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record RestoreReport(
        @NotNull UUID playerId,
        long snapshotId,
        @NotNull SyncResult result,
        @NotNull List<ModuleResult> modules,
        @NotNull Instant completedAt) implements SyncReport {

    /**
     * Canonical constructor with validation and normalization.
     *
     * @throws NullPointerException when {@code playerId}, {@code result}, {@code modules}
     *         or {@code completedAt} is null
     * @throws IllegalArgumentException when {@code snapshotId} is below 1
     */
    public RestoreReport {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(completedAt, "completedAt");
        if (snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1, got " + snapshotId);
        }
        modules = List.copyOf(modules);
    }
}
