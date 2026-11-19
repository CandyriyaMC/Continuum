package lol.candyriya.continuum.common.error;

/**
 * Storage-layer failures. Instantiated by the storage modules, declared here
 * because every tier of the stack needs to name them without depending on
 * storage.
 */
public final class StorageException extends ContinuumException {

    private StorageException(ErrorCode code, String message, Throwable cause) {
        super(code, message, cause);
    }

    /** The database is unreachable, timed out, or rejected the operation. */
    public static StorageException unavailable(String message, Throwable cause) {
        return new StorageException(ErrorCode.STORAGE_UNAVAILABLE, message, cause);
    }

    /** A write lost the fencing race against a newer session token. */
    public static StorageException fencingConflict(String message) {
        return new StorageException(ErrorCode.FENCING_CONFLICT, message, null);
    }
}
