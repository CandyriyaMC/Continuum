package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame exceeded the configured size cap. Thrown before a single byte of
 * the payload is interpreted — the cap is the first thing checked on decode.
 */
public final class FrameTooLargeException extends PluginMessageException {

    private final int sizeBytes;
    private final int maxBytes;

    public FrameTooLargeException(int sizeBytes, int maxBytes) {
        super(ErrorCode.PROTOCOL_VIOLATION, "frame is " + sizeBytes + " bytes, limit is " + maxBytes);
        this.sizeBytes = sizeBytes;
        this.maxBytes = maxBytes;
    }

    /** Actual frame size in bytes. */
    public int sizeBytes() {
        return sizeBytes;
    }

    /** Configured cap that was exceeded. */
    public int maxBytes() {
        return maxBytes;
    }
}
