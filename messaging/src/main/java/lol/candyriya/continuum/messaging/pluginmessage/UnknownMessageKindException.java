package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame's kind byte does not map to any known message kind. Receivers
 * must never guess the payload layout of an unknown kind; reject the frame.
 */
public final class UnknownMessageKindException extends PluginMessageException {

    private final int kind;

    public UnknownMessageKindException(int kind) {
        super(ErrorCode.PROTOCOL_VIOLATION, "unknown message kind 0x" + String.format("%02x", kind & 0xff));
        this.kind = kind & 0xff;
    }

    /** The kind byte from the frame, as an unsigned value. */
    public int kind() {
        return kind;
    }
}
