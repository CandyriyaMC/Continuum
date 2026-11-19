package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * The frame does not start with the 4-byte magic marker. Means either a
 * corrupt frame or, far more likely, someone sprayed this channel with
 * traffic that was never meant for us — drop it and log, never negotiate.
 */
public final class InvalidMagicException extends PluginMessageException {

    private final String actualMagic;

    public InvalidMagicException(byte[] actual) {
        super(ErrorCode.PROTOCOL_VIOLATION,
                "frame magic does not match " + PluginMessageProtocol.magicDisplayName() + ", got " + hex(actual));
        this.actualMagic = hex(actual);
    }

    /** What we actually found, as hex, for logs. */
    public String actualMagic() {
        return actualMagic;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2 + 2);
        sb.append("0x");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
