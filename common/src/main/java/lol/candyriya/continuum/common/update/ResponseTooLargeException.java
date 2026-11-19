package lol.candyriya.continuum.common.update;

import java.io.IOException;

/** A response body was bigger than the caller's cap; whatever was read is discarded. */
public final class ResponseTooLargeException extends IOException {

    public ResponseTooLargeException(String message) {
        super(message);
    }
}
