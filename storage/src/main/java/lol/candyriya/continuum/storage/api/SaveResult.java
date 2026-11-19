package lol.candyriya.continuum.storage.api;

/**
 * Outcome of a guarded snapshot write. Stale-write rejection is a
 * first-class result, not control flow via exceptions: callers branch on the
 * type, they do not catch a specific failure for it.
 */
public sealed interface SaveResult permits SaveResult.Saved, SaveResult.StaleToken {

    /**
     * The write won. {@code historySnapshotId} is the id of the history row
     * recorded by this save — handy for audit trails and rollback commands.
     */
    record Saved(long historySnapshotId) implements SaveResult {

        public Saved {
            if (historySnapshotId < 1) {
                throw new IllegalArgumentException("historySnapshotId must be >= 1, got " + historySnapshotId);
            }
        }
    }

    /**
     * The write lost the fencing race: another session already owns a snapshot
     * with a token at or above the one supplied. Nothing was modified.
     *
     * @param currentFencingToken the fencing token currently stored; always
     *                            greater than or equal to the rejected one
     */
    record StaleToken(long currentFencingToken) implements SaveResult {

        public StaleToken {
            if (currentFencingToken < 0) {
                throw new IllegalArgumentException("currentFencingToken must be >= 0, got " + currentFencingToken);
            }
        }
    }

    static SaveResult saved(long historySnapshotId) {
        return new Saved(historySnapshotId);
    }

    static SaveResult staleToken(long currentFencingToken) {
        return new StaleToken(currentFencingToken);
    }

    /** True when the write landed. */
    default boolean saved() {
        return this instanceof Saved;
    }
}
