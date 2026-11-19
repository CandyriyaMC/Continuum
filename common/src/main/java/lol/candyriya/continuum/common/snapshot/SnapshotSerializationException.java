package lol.candyriya.continuum.common.snapshot;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/** Serialization or deserialization of a snapshot failed (bytes, size guard, schema). */
public final class SnapshotSerializationException extends ContinuumException {

    public SnapshotSerializationException(String message) {
        super(ErrorCode.SERIALIZATION_FAILED, message);
    }

    public SnapshotSerializationException(String message, Throwable cause) {
        super(ErrorCode.SERIALIZATION_FAILED, message, cause);
    }
}
