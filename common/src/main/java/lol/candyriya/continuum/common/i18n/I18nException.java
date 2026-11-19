package lol.candyriya.continuum.common.i18n;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/** A message catalog could not be loaded or parsed. */
public final class I18nException extends ContinuumException {

    public I18nException(String message) {
        super(ErrorCode.I18N_ERROR, message);
    }

    public I18nException(String message, Throwable cause) {
        super(ErrorCode.I18N_ERROR, message, cause);
    }
}
