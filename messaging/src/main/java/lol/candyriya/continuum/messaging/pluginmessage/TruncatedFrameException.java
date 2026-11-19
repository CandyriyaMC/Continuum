package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame ends before the declared payload does. Either it really was cut
 * short on the wire (should not happen on TCP, so treat it as corruption) or
 * the length header lied. Both mean: reject.
 */
public final class TruncatedFrameException extends PluginMessageException {

    private final int expectedBytes;
    private final int actualBytes;

    public TruncatedFrameException(int expectedBytes, int actualBytes) {
        super(ErrorCode.PROTOCOL_VIOLATION,
                "frame needs " + expectedBytes + " bytes, got " + actualBytes);
        this.expectedBytes = expectedBytes;
        this.actualBytes = actualBytes;
    }

    /** Bytes the header says this frame should occupy. */
    public int expectedBytes() {
        return expectedBytes;
    }

    /** Bytes actually present. */
    public int actualBytes() {
        return actualBytes;
    }
}
