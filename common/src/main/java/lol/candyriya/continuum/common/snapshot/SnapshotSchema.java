package lol.candyriya.continuum.common.snapshot;

/**
 * Current snapshot schema version. Bump when the on-disk shape changes in a
 * way old readers cannot tolerate; register a {@link SnapshotMigration} for
 * the upgrade path.
 */
public final class SnapshotSchema {

    /** Latest schema version written by this build. */
    public static final int CURRENT_VERSION = 1;

    private SnapshotSchema() {
    }
}
