package lol.candyriya.continuum.velocity.session;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/** Raised when a session token cannot be issued. */
public class SessionTokenException extends ContinuumException {

    public SessionTokenException(String message) {
        super(ErrorCode.STORAGE_UNAVAILABLE, message);
    }

    public SessionTokenException(String message, Throwable cause) {
        super(ErrorCode.STORAGE_UNAVAILABLE, message, cause);
    }
}
