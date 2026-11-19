package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * A schema migration could not be applied or the migration table does not match
 * this build. Fatal for the storage backend: implementations must refuse to
 * serve and the caller must disable storage, never "hope it works anyway".
 */
public final class SchemaMigrationException extends ContinuumException {

    public SchemaMigrationException(String message) {
        super(ErrorCode.MIGRATION_FAILED, message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(ErrorCode.MIGRATION_FAILED, message, cause);
    }
}
