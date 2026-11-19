package lol.candyriya.continuum.messaging.pluginmessage;

/**
 * Binary framing constants for the {@code continuum:main} plugin message
 * channel. The layout is fixed, big-endian, and stable across protocol
 * version 1:
 *
 * <pre>
 * offset  size  field
 * 0       4     magic, ASCII "ctmm" (0x63 0x74 0x6D 0x6D)
 * 4       1     protocol version (currently 1)
 * 5       1     message kind, see {@link PluginMessageKind}
 * 6       8     correlation id, most significant bits of the UUID
 * 14      8     correlation id, least significant bits of the UUID
 * 22      4     payload length in bytes, big-endian, 0..{@code maxPayloadBytes}
 * 26      N     payload, UTF-8 JSON for the kind's record
 * </pre>
 *
 * <p>Total header is {@value #HEADER_BYTES} bytes. The magic marker is not a
 * security boundary (nothing on this channel is); it exists so misrouted or
 * corrupt frames fail fast with a precise error instead of decoding into
 * garbage. All versioning decisions live in the version byte; all
 * authentication decisions live in the transport (modern forwarding plus a
 * proxy that refuses client-sent copies of this channel).</p>
 */
public final class PluginMessageProtocol {

    /** Magic marker: ASCII "ctmm". */
    static final byte[] MAGIC = {0x63, 0x74, 0x6D, 0x6D};

    /** Protocol version this build writes. */
    public static final int CURRENT_VERSION = 1;

    /** Oldest version this build can read. Everything below is rejected. */
    public static final int MIN_SUPPORTED_VERSION = 1;

    /** Total header size in bytes: magic + version + kind + UUID + payload length. */
    public static final int HEADER_BYTES = 4 + 1 + 1 + 16 + 4;

    static final int OFFSET_VERSION = 4;
    static final int OFFSET_KIND = 5;
    static final int OFFSET_CORRELATION_MSB = 6;
    static final int OFFSET_CORRELATION_LSB = 14;
    static final int OFFSET_PAYLOAD_LENGTH = 22;
    static final int OFFSET_PAYLOAD = HEADER_BYTES;

    private PluginMessageProtocol() {
    }

    /** Whether this build can read frames of {@code version}. */
    public static boolean isSupported(int version) {
        return version >= MIN_SUPPORTED_VERSION && version <= CURRENT_VERSION;
    }

    /** Human-readable magic for error messages, e.g. {@code "ctmm (0x63746d6d)"}. */
    public static String magicDisplayName() {
        return "\"ctmm\" (0x63746d6d)";
    }

    /** Reads the 4 bytes at the start of {@code frame} as hex, for diagnostics. */
    static byte[] magicOf(byte[] frame) {
        byte[] magic = new byte[MAGIC.length];
        System.arraycopy(frame, 0, magic, 0, Math.min(MAGIC.length, frame.length));
        return magic;
    }

    /** Whether the frame starts with the magic marker. */
    static boolean hasMagic(byte[] frame) {
        if (frame.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (frame[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }
}
