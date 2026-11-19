package lol.candyriya.continuum.common.update;

import java.io.IOException;

/** The server answered with a redirect we are not allowed to follow. Caught by the check path; the cycle just ends. */
public final class RedirectNotAllowedException extends IOException {

    public RedirectNotAllowedException(String message) {
        super(message);
    }
}
