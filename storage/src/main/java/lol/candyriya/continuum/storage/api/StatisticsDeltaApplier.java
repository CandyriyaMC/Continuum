package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.error.StorageException;

import java.util.Map;
import java.util.UUID;

/**
 * Accumulate-mutations for statistics (for example "games played +1").
 *
 * <p>Deltas are applied with an atomic {@code SET stat = stat + ?} upsert
 * inside the database, never as read-modify-write from application memory:
 * concurrent backends incrementing the same stat cannot lose updates, no
 * fencing token is required for this path.</p>
 */
public interface StatisticsDeltaApplier {

    /**
     * Adds {@code delta} to one stat of one player, creating the row when absent.
     *
     * @param playerId the player, never null
     * @param statKey  non-blank, at most 64 characters
     * @param delta    the increment; may be negative (stats may go down)
     * @return the new stored value
     * @throws StorageException when the database cannot complete the operation
     */
    long applyDelta(UUID playerId, String statKey, long delta) throws StorageException;

    /**
     * Adds many deltas in one transaction. Keys are applied in map iteration
     * order; on failure nothing is committed.
     *
     * @return the new stored value for every key that was updated
     * @throws StorageException when the database cannot complete the operation
     */
    Map<String, Long> applyDeltas(UUID playerId, Map<String, Long> deltas) throws StorageException;
}
