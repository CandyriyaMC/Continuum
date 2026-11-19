package lol.candyriya.continuum.api.snapshot;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.UUID;

/**
 * Thrown (or used to complete a stage exceptionally) when a snapshot id is referenced
 * that does not exist, or exists but belongs to a different player.
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class SnapshotNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The player the lookup was scoped to. */
    private final UUID playerId;
    /** The missing snapshot id. */
    private final long snapshotId;

    /**
     * Creates the exception.
     *
     * @param playerId the player the lookup was scoped to, never null
     * @param snapshotId the missing snapshot id, &ge; 1
     */
    public SnapshotNotFoundException(@NotNull UUID playerId, long snapshotId) {
        super("snapshot " + snapshotId + " not found for player " + playerId);
        this.playerId = playerId;
        this.snapshotId = snapshotId;
    }

    /**
     * The player the lookup was scoped to.
     *
     * @return the player UUID, never null
     */
    @NotNull
    public UUID playerId() {
        return playerId;
    }

    /**
     * The missing snapshot id.
     *
     * @return the snapshot id, &ge; 1
     */
    public long snapshotId() {
        return snapshotId;
    }
}
