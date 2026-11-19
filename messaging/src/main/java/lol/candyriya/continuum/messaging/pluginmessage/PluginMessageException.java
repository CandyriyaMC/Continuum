package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * Base class for every failure this package raises.
 *
 * <p>Unchecked and carries a stable {@link ErrorCode}, matching the rest of
 * the codebase. The concrete subtype tells you which stage of the pipeline
 * refused the frame — handlers should log with context and never rethrow raw
 * platform exceptions from the network callback.</p>
 */
public abstract class PluginMessageException extends ContinuumException {

    protected PluginMessageException(ErrorCode code, String message) {
        super(code, message);
    }

    protected PluginMessageException(ErrorCode code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
