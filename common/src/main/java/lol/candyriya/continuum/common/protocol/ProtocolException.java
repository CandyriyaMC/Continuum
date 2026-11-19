package lol.candyriya.continuum.common.protocol;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/** A wire message failed structural validation or exceeded size limits. */
public final class ProtocolException extends ContinuumException {

    public ProtocolException(String message) {
        super(ErrorCode.PROTOCOL_VIOLATION, message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(ErrorCode.PROTOCOL_VIOLATION, message, cause);
    }
}
