package lol.candyriya.continuum.paper.modules.pdc;

import org.jetbrains.annotations.Nullable;

/**
 * Wire-level value types for captured PDC entries.
 *
 * <p>Mirrors the subset of {@code PersistentDataType} the module can carry losslessly.
 * The enum name is the wire name in snapshots - it appears in stored JSON, so renaming
 * a constant is a schema bump, never a silent rename. The set is deliberately closed:
 * anything the container can hold that is not listed here is skipped at capture time
 * rather than synced lossily.</p>
 *
 * <p>{@code BOOLEAN} is missing on purpose: PDC has no native booleans. Plugins that
 * use the convenience {@code PersistentDataType.BOOLEAN} actually persist a byte 0/1,
 * which round-trips as {@link #BYTE} and stays readable as a boolean on both sides.
 * {@code TAG_CONTAINER_ARRAY} is missing because it is deprecated upstream; plain
 * {@code TAG_LIST} values (via {@code PersistentDataType.LIST}) are missing because
 * an empty list carries no element type to introspect and a wrong guess would corrupt
 * data on restore - they are reported as skipped instead.</p>
 */
public enum PdcValueType {

    BYTE,
    SHORT,
    INTEGER,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    BYTE_ARRAY,
    INTEGER_ARRAY,
    LONG_ARRAY,
    TAG_CONTAINER;

    /** @return true when the value travels as base64 inside the payload */
    public boolean isBase64Carried() {
        return this == BYTE_ARRAY || this == INTEGER_ARRAY || this == LONG_ARRAY || this == TAG_CONTAINER;
    }

    /** @return true when the payload value is an integral (Long) */
    public boolean isIntegral() {
        return this == BYTE || this == SHORT || this == INTEGER || this == LONG;
    }

    /** @return true for the real-valued primitives (Float/Double) */
    public boolean isReal() {
        return this == FLOAT || this == DOUBLE;
    }

    /**
     * Looks up a type by its wire name.
     *
     * @param name the exact wire name ({@link #name()}), never null
     * @return the matching type, or null when the name is unknown (e.g. written by a newer writer)
     */
    public static @Nullable PdcValueType find(String name) {
        for (PdcValueType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
