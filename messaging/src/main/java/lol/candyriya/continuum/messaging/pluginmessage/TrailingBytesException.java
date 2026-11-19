package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame carries more bytes than the declared payload accounts for.
 * Deliberately not tolerated: silently ignoring trailing garbage is how
 * padding oracles and smuggling bugs grow.
 */
public final class TrailingBytesException extends PluginMessageException {

    private final int trailingBytes;

    public TrailingBytesException(int trailingBytes) {
        super(ErrorCode.PROTOCOL_VIOLATION, "frame has " + trailingBytes + " trailing bytes after the payload");
        this.trailingBytes = trailingBytes;
    }

    /** Number of surplus bytes after the declared payload. */
    public int trailingBytes() {
        return trailingBytes;
    }
}
