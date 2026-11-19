package lol.candyriya.continuum.paper.modules.pdc;

import java.util.Objects;

/**
 * One captured PDC entry.
 *
 * <p>The {@code value} is normalized so the wire shape is stable regardless of what
 * the platform handed us:</p>
 * <ul>
 *   <li>{@link PdcValueType#BYTE}/{@link PdcValueType#SHORT}/{@link PdcValueType#INTEGER}/{@link PdcValueType#LONG} - always {@link Long}, range-checked</li>
 *   <li>{@link PdcValueType#FLOAT}/{@link PdcValueType#DOUBLE} - always {@link Double}; widening float&rarr;double&rarr;float is exact, so no precision is lost</li>
 *   <li>{@link PdcValueType#STRING} - always {@link String}</li>
 *   <li>binary types - always {@link String} holding base64; layout of int/long arrays is big-endian</li>
 * </ul>
 *
 * <p>The canonical (compact) constructor runs on JSON deserialization too, so a
 * hand-forged or corrupt snapshot is rejected here with {@link IllegalArgumentException}
 * before it ever reaches the restore path.</p>
 *
 * @param key   the namespaced key, e.g. {@code myplugin:flag}; shape is checked by the validator, not here
 * @param type  the wire type, never null
 * @param value the normalized value, never null
 */
public record PdcEntry(String key, PdcValueType type, Object value) {

    public PdcEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        value = normalize(type, value);
    }

    private static Object normalize(PdcValueType type, Object raw) {
        switch (type) {
            case BYTE, SHORT, INTEGER, LONG -> {
                return PdcCodec.toIntegral(type, raw);
            }
            case FLOAT, DOUBLE -> {
                if (!(raw instanceof Number number)) {
                    throw new IllegalArgumentException(
                            "expected a number for " + type + ", got " + raw.getClass().getSimpleName());
                }
                double d = number.doubleValue();
                // JSON has no NaN/Infinity - a snapshot carrying one could never be read back
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("non-finite value for " + type + ": " + d);
                }
                return d;
            }
            case STRING -> {
                if (!(raw instanceof String s)) {
                    throw new IllegalArgumentException(
                            "expected a string for STRING, got " + raw.getClass().getSimpleName());
                }
                return s;
            }
            case BYTE_ARRAY -> {
                return checkBase64(type, raw, -1);
            }
            case INTEGER_ARRAY -> {
                return checkBase64(type, raw, Integer.BYTES);
            }
            case LONG_ARRAY -> {
                return checkBase64(type, raw, Long.BYTES);
            }
            case TAG_CONTAINER -> {
                // the NBT payload is not parsed here; the restore path validates it with a real try/catch
                return checkBase64(type, raw, -1);
            }
            default -> throw new IllegalStateException("unhandled type " + type);
        }
    }

    private static String checkBase64(PdcValueType type, Object raw, int elementWidth) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException(
                    "expected a base64 string for " + type + ", got " + raw.getClass().getSimpleName());
        }
        byte[] decoded;
        try {
            decoded = PdcCodec.decodeBase64(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("value for " + type + " is not valid base64", e);
        }
        if (elementWidth > 0 && decoded.length % elementWidth != 0) {
            throw new IllegalArgumentException(
                    "value for " + type + " decodes to " + decoded.length + " bytes, not a multiple of " + elementWidth);
        }
        return s;
    }

    /** @return {@link #value()} as a long; only legal on integral types */
    public long asIntegral() {
        return ((Long) value());
    }

    /** @return {@link #value()} as a double; only legal on real types */
    public double asReal() {
        return ((Double) value());
    }

    /** @return {@link #value()} as a string; legal on STRING and all base64-carried types */
    public String asString() {
        return (String) value();
    }
}
