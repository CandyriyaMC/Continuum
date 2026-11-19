package lol.candyriya.continuum.api.history;

import lol.candyriya.continuum.api.report.RestoreReport;
import lol.candyriya.continuum.api.snapshot.PlayerSnapshot;
import lol.candyriya.continuum.api.snapshot.SnapshotNotFoundException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read access to snapshot history and the one destructive-looking-but-isn't operation:
 * rollback.
 *
 * <p>Obtained via {@code ContinuumProvider.get().history()}. All methods may be called
 * from any thread; storage-backed operations return a future that completes on
 * Continuum's infrastructure executor.</p>
 *
 * <p><b>Retention.</b> Continuum keeps the configured number of snapshots per player and
 * never deletes the snapshot that is currently the basis of a player's state as a side
 * effect of reading history or running a rollback. Retention trimming happens, when
 * configured, after successful captures and is independent of these methods.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface HistoryService {

    /**
     * The newest snapshots for a player, newest first.
     *
     * @param playerId the player, never null
     * @param limit the maximum number of entries to return; must be &ge; 1
     * @return the snapshots, never null, possibly empty; unmodifiable; iteration order is newest first
     * @throws NullPointerException when {@code playerId} is null
     * @throws IllegalArgumentException when {@code limit} is below 1
     */
    @NotNull
    CompletableFuture<List<PlayerSnapshot>> list(@NotNull UUID playerId, int limit);

    /**
     * Rolls the player's state back to a stored snapshot.
     *
     * <p>Rollback never deletes data. It creates a <em>new</em> snapshot — a copy of the
     * chosen historical one under a fresh id, marked with reason
     * {@code ROLLBACK} — and makes that the basis for the player's next restore. The
     * snapshot that was current before the rollback is left in place, untouched and
     * readable.</p>
     *
     * <p>If the player is online on this backend, the rolled-back state is applied live;
     * the returned future completes when the live restore completes and the report
     * describes the per-module outcomes. If the player is offline, no live restore
     * happens: the rollback is recorded and takes effect on the player's next restore;
     * the report contains no module rows and
     * {@link lol.candyriya.continuum.api.snapshot.SyncResult#SUCCESS} means the rollback
     * was committed to storage, not that any module ran.</p>
     *
     * <p>The write is fenced: it commits only while this backend holds the player's
     * current fencing token. Continuum therefore never applies a rollback over a newer
     * transfer.</p>
     *
     * <p>Requires administrative authority by convention — the platform plugin is expected
     * to gate this behind a permission. The API itself is deliberately not permission
     * aware.</p>
     *
     * @param playerId the player, never null
     * @param snapshotId the snapshot to roll back to, &ge; 1
     * @return the rollback outcome; never completes null
     * @throws NullPointerException when {@code playerId} is null
     * @throws IllegalArgumentException when {@code snapshotId} is below 1
     * @throws SnapshotNotFoundException via the returned future, when the snapshot does
     *         not exist or does not belong to the player
     */
    @NotNull
    CompletableFuture<RestoreReport> rollback(@NotNull UUID playerId, long snapshotId);
}
