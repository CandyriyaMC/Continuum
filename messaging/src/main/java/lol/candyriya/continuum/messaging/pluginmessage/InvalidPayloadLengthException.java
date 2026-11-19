package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/** The declared payload length field is negative — a nonsense frame. */
public final class InvalidPayloadLengthException extends PluginMessageException {

    private final int declaredLength;

    public InvalidPayloadLengthException(int declaredLength) {
        super(ErrorCode.PROTOCOL_VIOLATION, "declared payload length is negative: " + declaredLength);
        this.declaredLength = declaredLength;
    }

    /** The offending length value from the header. */
    public int declaredLength() {
        return declaredLength;
    }
}
