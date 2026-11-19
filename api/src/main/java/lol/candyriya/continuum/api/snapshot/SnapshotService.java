package lol.candyriya.continuum.api.snapshot;

import lol.candyriya.continuum.api.player.PlayerNotOnlineException;
import lol.candyriya.continuum.api.report.CaptureReport;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read access to stored snapshots and the ability to trigger a fresh capture.
 *
 * <p>Obtained via {@code ContinuumProvider.get().snapshots()}. All methods may be called
 * from any thread; storage-backed operations return a future that completes on
 * Continuum's infrastructure executor, never on a game thread. No method performs
 * blocking I/O on the calling thread.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface SnapshotService {

    /**
     * Captures the current online state of a player and persists it as a new snapshot.
     *
     * <p>The capture itself runs on the thread owning the player (Paper main thread, Folia
     * entity region thread); persistence happens on Continuum's infrastructure executor.
     * The returned future completes only after the snapshot's fenced database write has
     * committed — from that moment it is visible to {@link #latest(UUID)} and to
     * {@code history()} calls. It never completes {@code null} and never completes
     * {@link lol.candyriya.continuum.api.report.CaptureReport} with a missing
     * {@code snapshotId} when required modules succeeded.</p>
     *
     * @param playerId the player to capture; must be online on this backend
     * @param reason why the capture is happening; pass {@link SnapshotReason#API} when
     *        triggered by third-party code
     * @return the capture report; never completes null
     * @throws NullPointerException when any argument is null
     * @throws PlayerNotOnlineException via the returned future, when the player is not online
     */
    @NotNull
    CompletableFuture<CaptureReport> capture(@NotNull UUID playerId, @NotNull SnapshotReason reason);

    /**
     * The newest snapshot metadata for a player, regardless of which backend captured it.
     *
     * @param playerId the player, never null
     * @return the snapshot, or empty when the player has none
     * @throws NullPointerException when {@code playerId} is null
     */
    @NotNull
    CompletableFuture<Optional<PlayerSnapshot>> latest(@NotNull UUID playerId);

    /**
     * Fetches the metadata of a specific stored snapshot.
     *
     * <p>The lookup is always scoped to the given player — a snapshot that exists but
     * belongs to someone else is reported as absent, not leaked.</p>
     *
     * @param playerId the owning player, never null
     * @param snapshotId the snapshot id, &ge; 1
     * @return the snapshot, or empty when it does not exist or does not belong to the player
     * @throws NullPointerException when {@code playerId} is null
     * @throws IllegalArgumentException when {@code snapshotId} is below 1
     */
    @NotNull
    CompletableFuture<Optional<PlayerSnapshot>> get(@NotNull UUID playerId, long snapshotId);
}
