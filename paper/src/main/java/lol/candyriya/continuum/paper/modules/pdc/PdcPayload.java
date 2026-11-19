package lol.candyriya.continuum.paper.modules.pdc;

import java.util.List;
import java.util.Objects;

/**
 * Wire payload for {@code continuum.pdc} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys, enums as names). Example:</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "entries": [
 *     {"key": "rpgclasses:primary_class", "type": "STRING", "value": "warrior"},
 *     {"key": "quests:completed", "type": "TAG_CONTAINER", "value": "H4sIAA..."},
 *     {"key": "cosmetics:trail_enabled", "type": "BYTE", "value": 1}
 *   ]
 * }
 * }</pre>
 *
 * <p>Numbers are JSON numbers ({@link Long}/{@link Double}), strings are JSON strings,
 * everything binary is base64. {@code TAG_CONTAINER} values travel as base64 of the
 * nested container's own byte serialization, which is byte-compatible with the
 * matching platform's PDC implementation on both ends.</p>
 *
 * <p>The format field is the schema version stored inside the payload; the module's
 * {@code version()} reports the same number as the Continuum schema marker. Bump both
 * together on any incompatible shape change.</p>
 *
 * @param format  payload format version; {@link #FORMAT} today, anything else is rejected by validation
 * @param entries the captured entries, never null; duplicate keys are rejected by validation, not here
 */
public record PdcPayload(int format, List<PdcEntry> entries) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    public PdcPayload {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /** A payload at the current format with the given entries. */
    public static PdcPayload of(List<PdcEntry> entries) {
        return new PdcPayload(FORMAT, entries);
    }

    /** @return the decoded footprint of all entries in bytes */
    public long totalStoredBytes() {
        long total = 0;
        for (PdcEntry entry : entries) {
            total += PdcCodec.storedSize(entry.type(), entry.value());
        }
        return total;
    }
}
