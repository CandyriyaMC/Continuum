package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The payload is UTF-8 but not a JSON object this build can bind to the
 * declared kind — broken JSON, a non-object document, or a document whose
 * shape does not match the kind's record. The cause carries the parser's
 * detail when there is one.
 */
public final class MalformedPayloadException extends PluginMessageException {

    private final String wireId;

    public MalformedPayloadException(String wireId, String detail) {
        super(ErrorCode.PROTOCOL_VIOLATION, "payload for '" + wireId + "' is malformed: " + detail);
        this.wireId = wireId;
    }

    public MalformedPayloadException(String wireId, String detail, Throwable cause) {
        super(ErrorCode.PROTOCOL_VIOLATION, "payload for '" + wireId + "' is malformed: " + detail, cause);
        this.wireId = wireId;
    }

    /** Dotted wire id of the kind that failed to decode, e.g. {@code continuum.handoff.request}. */
    public String wireId() {
        return wireId;
    }
}
