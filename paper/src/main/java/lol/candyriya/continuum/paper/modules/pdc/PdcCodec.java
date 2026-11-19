package lol.candyriya.continuum.paper.modules.pdc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Pure encoding plumbing for PDC values: base64 for the binary types, big-endian
 * byte layout for int/long arrays, size and key-legality checks. No server, no
 * Bukkit - everything here is unit-testable on plain JUnit.
 */
public final class PdcCodec {

    // minecraft's resource location rules: namespace plus path, both lowercase
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[a-z0-9._-]{1,64}:[a-z0-9._/-]{1,256}$");

    private PdcCodec() {
        // static access only
    }

    // ---------- base64 ----------

    /** Encodes raw bytes as standard base64 (RFC 4648, with padding). */
    public static String encodeBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Decodes standard base64.
     *
     * @throws IllegalArgumentException when the input is not valid base64
     */
    public static byte[] decodeBase64(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // decode() throws on bad alphabet and bad padding; wrap with context, keep the cause
            throw new IllegalArgumentException("not valid base64", e);
        }
    }

    // ---------- int[] / long[] ----------

    /** Encodes an int array as big-endian bytes, then base64. Layout is part of the wire format. */
    public static String encodeIntArray(int[] values) {
        var buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int value : values) {
            buffer.putInt(value);
        }
        return encodeBase64(buffer.array());
    }

    /** Decodes what {@link #encodeIntArray(int[])} produced. */
    public static int[] decodeIntArray(String encoded) {
        byte[] bytes = decodeBase64(encoded);
        if (bytes.length % Integer.BYTES != 0) {
            throw new IllegalArgumentException("int array base64 decodes to " + bytes.length + " bytes, not a multiple of 4");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int[] values = new int[bytes.length / Integer.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getInt();
        }
        return values;
    }

    /** Encodes a long array as big-endian bytes, then base64. Layout is part of the wire format. */
    public static String encodeLongArray(long[] values) {
        var buffer = ByteBuffer.allocate(values.length * Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (long value : values) {
            buffer.putLong(value);
        }
        return encodeBase64(buffer.array());
    }

    /** Decodes what {@link #encodeLongArray(long[])} produced. */
    public static long[] decodeLongArray(String encoded) {
        byte[] bytes = decodeBase64(encoded);
        if (bytes.length % Long.BYTES != 0) {
            throw new IllegalArgumentException("long array base64 decodes to " + bytes.length + " bytes, not a multiple of 8");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long[] values = new long[bytes.length / Long.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getLong();
        }
        return values;
    }

    // ---------- sizes (decoded footprints) ----------

    /**
     * Size of a live value as read from the container. Scalars report their storage
     * width, strings their UTF-8 byte length, arrays their element count times the
     * element width.
     *
     * @param type the type the value was read with
     * @param raw  the raw platform value (Byte/Short/Integer/Long/Float/Double/String/byte[]/int[]/long[]); for
     *             {@link PdcValueType#TAG_CONTAINER} this must be the serialized bytes of the nested container
     */
    public static long liveSize(PdcValueType type, Object raw) {
        return switch (type) {
            case BYTE -> Byte.BYTES;
            case SHORT -> Short.BYTES;
            case INTEGER -> Integer.BYTES;
            case LONG -> Long.BYTES;
            case FLOAT -> Float.BYTES;
            case DOUBLE -> Double.BYTES;
            case STRING -> utf8Length((String) raw);
            case BYTE_ARRAY -> ((byte[]) raw).length;
            case INTEGER_ARRAY -> (long) ((int[]) raw).length * Integer.BYTES;
            case LONG_ARRAY -> (long) ((long[]) raw).length * Long.BYTES;
            case TAG_CONTAINER -> ((byte[]) raw).length;
        };
    }

    /**
     * Size of a value as stored in the payload. Numeric payloads are always
     * Long/Double, strings plain Strings, everything binary is base64.
     */
    public static long storedSize(PdcValueType type, Object payloadValue) {
        return switch (type) {
            case BYTE -> Byte.BYTES;
            case SHORT -> Short.BYTES;
            case INTEGER -> Integer.BYTES;
            case LONG -> Long.BYTES;
            case FLOAT -> Float.BYTES;
            case DOUBLE -> Double.BYTES;
            case STRING -> utf8Length((String) payloadValue);
            // base64 decode cost is capped by the per-key limit: 64 KiB decodes in microseconds
            case BYTE_ARRAY, INTEGER_ARRAY, LONG_ARRAY, TAG_CONTAINER -> decodeBase64((String) payloadValue).length;
        };
    }

    /** UTF-8 encoded length of {@code value}. */
    public static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    // ---------- key legality ----------

    /** @return true when {@code key} is a legal {@code namespace:path} namespaced key */
    public static boolean isWellFormedKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }

    /** @return the namespace part of a well-formed key, or null when the key is malformed */
    public static String namespaceOf(String key) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return null;
        }
        return key.substring(0, colon);
    }

    // ---------- number handling ----------

    /**
     * Normalizes a numeric payload value to a Long within the range of the
     * given integral type.
     *
     * @throws IllegalArgumentException when the value is out of range or not a number
     */
    public static long toIntegral(PdcValueType type, Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "expected a number for " + type + ", got " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        long v = number.longValue();
        switch (type) {
            case BYTE -> requireRange(v, Byte.MIN_VALUE, Byte.MAX_VALUE, type);
            case SHORT -> requireRange(v, Short.MIN_VALUE, Short.MAX_VALUE, type);
            case INTEGER -> requireRange(v, Integer.MIN_VALUE, Integer.MAX_VALUE, type);
            default -> {
                // LONG keeps the full range
            }
        }
        return v;
    }

    private static void requireRange(long v, long min, long max, PdcValueType type) {
        if (v < min || v > max) {
            throw new IllegalArgumentException("value " + v + " does not fit in PDC " + type);
        }
    }
}
