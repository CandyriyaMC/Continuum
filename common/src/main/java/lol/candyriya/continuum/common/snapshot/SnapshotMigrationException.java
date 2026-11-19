package lol.candyriya.continuum.common.snapshot;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/** A snapshot migration step failed or the chain has a gap. */
public final class SnapshotMigrationException extends ContinuumException {

    public SnapshotMigrationException(String message) {
        super(ErrorCode.MIGRATION_FAILED, message);
    }

    public SnapshotMigrationException(String message, Throwable cause) {
        super(ErrorCode.MIGRATION_FAILED, message, cause);
    }
}
