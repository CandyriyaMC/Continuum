package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame's protocol version byte is not supported by this build.
 * Deliberately explicit — guessing at a foreign format is how parsers get
 * owned. Bump {@link PluginMessageProtocol#CURRENT_VERSION} on both sides
 * instead of squinting.
 */
public final class UnsupportedProtocolVersionException extends PluginMessageException {

    private final int version;

    public UnsupportedProtocolVersionException(int version) {
        super(ErrorCode.PROTOCOL_VIOLATION,
                "unsupported protocol version " + version
                        + " (supported: " + PluginMessageProtocol.MIN_SUPPORTED_VERSION
                        + ".." + PluginMessageProtocol.CURRENT_VERSION + ")");
        this.version = version;
    }

    /** The version byte from the frame. */
    public int version() {
        return version;
    }
}
