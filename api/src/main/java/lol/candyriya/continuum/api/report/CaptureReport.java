package lol.candyriya.continuum.api.report;

import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The outcome of a snapshot capture operation.
 *
 * @param playerId the player that was captured, never null
 * @param snapshotId the id of the persisted snapshot, or null when no snapshot was
 *        persisted (a required module failed); never present for offline/precondition
 *        failures, which complete the capture stage exceptionally instead
 * @param reason why the snapshot was taken, never null
 * @param result the overall verdict, never null
 * @param modules the per-module rows in capture order, never null; copied — unmodifiable
 * @param completedAt when the snapshot was handed off for persistence, never null
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record CaptureReport(
        @NotNull UUID playerId,
        @Nullable Long snapshotId,
        @NotNull SnapshotReason reason,
        @NotNull SyncResult result,
        @NotNull List<ModuleResult> modules,
        @NotNull Instant completedAt) implements SyncReport {

    /**
     * Canonical constructor with validation and normalization.
     *
     * @throws NullPointerException when {@code playerId}, {@code reason}, {@code result},
     *         {@code modules} or {@code completedAt} is null
     */
    public CaptureReport {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(completedAt, "completedAt");
        modules = List.copyOf(modules);
    }

    /**
     * The id of the persisted snapshot, or empty when nothing was persisted.
     *
     * @return the snapshot id, if any
     */
    @NotNull
    public Optional<Long> snapshotIdIfPersisted() {
        return Optional.ofNullable(snapshotId);
    }
}
