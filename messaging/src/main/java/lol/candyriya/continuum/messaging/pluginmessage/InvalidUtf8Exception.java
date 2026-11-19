package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The payload bytes are not valid UTF-8. Checked explicitly before the JSON
 * parser gets involved so the failure maps to a precise, testable cause.
 */
public final class InvalidUtf8Exception extends PluginMessageException {

    public InvalidUtf8Exception() {
        super(ErrorCode.PROTOCOL_VIOLATION, "payload is not valid UTF-8");
    }
}
