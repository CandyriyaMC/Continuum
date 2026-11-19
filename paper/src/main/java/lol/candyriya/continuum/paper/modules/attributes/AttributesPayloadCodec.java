package lol.candyriya.continuum.paper.modules.attributes;

import lol.candyriya.continuum.api.module.ValidationResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The payload codec. The module's payload {@code T} is a plain
 * {@code Map<String, Object>} tree so it survives the shared JSON machinery untouched
 * — map keys are never renamed by a property naming strategy, so the wire format is
 * byte-identical to what this class builds:
 *
 * <pre>{
 *   "format": 1,
 *   "capturedAt": "2026-11-19T12:34:56.789Z",
 *   "attributes": [
 *     {
 *       "key": "minecraft:max_health",
 *       "base": 20.0,
 *       "modifiers": [
 *         { "key": "continuum:example", "amount": 1.0, "operation": "ADD_NUMBER", "slotGroup": "any" }
 *       ]
 *     }
 *   ]
 * }</pre>
 *
 * <p>Guarantees:</p>
 * <ul>
 *   <li><b>Versioned.</b> {@link #FORMAT} is written as {@code format}. Readers tolerate
 *       any integer ≥ 1; anything else is a validation failure. A newer major format
 *       decodes best-effort with a warning, never an exception.</li>
 *   <li><b>Deterministic.</b> Attributes are encoded in registry (capture) order —
 *       stable across captures on the same server. Modifiers inside an attribute are
 *       sorted by (key, operation, amount, slotGroup), so two captures of the same
 *       state produce the same payload.</li>
 *   <li><b>Lossy in only one place.</b> Unknown operation names are dropped with a
 *       warning at decode. Unknown attribute/modifier keys are skipped with a warning.
 *       Structural garbage fails validation before any state is mutated.</li>
 *   <li><b>No magic fallbacks.</b> Numbers are JSON numbers; strings are strings.
 *       Anything else is rejected.</li>
 * </ul>
 */
final class AttributesPayloadCodec {

    static final int FORMAT = 1;

    static final String FIELD_FORMAT = "format";
    static final String FIELD_CAPTURED_AT = "capturedAt";
    static final String FIELD_ATTRIBUTES = "attributes";
    static final String FIELD_KEY = "key";
    static final String FIELD_BASE = "base";
    static final String FIELD_MODIFIERS = "modifiers";
    static final String FIELD_AMOUNT = "amount";
    static final String FIELD_OPERATION = "operation";
    static final String FIELD_SLOT_GROUP = "slotGroup";

    private AttributesPayloadCodec() {
    }

    /** Encodes a capture into the wire-format map. Iteration order is part of the contract. */
    static Map<String, Object> encode(List<CapturedAttribute> attributes, Instant capturedAt) {
        var encodedAttributes = new ArrayList<Map<String, Object>>(attributes.size());
        for (CapturedAttribute attribute : attributes) {
            // CapturedAttribute keeps modifiers canonically sorted already; no re-sort needed
            var encodedModifiers = new ArrayList<Map<String, Object>>(attribute.modifiers().size());
            for (CapturedModifier modifier : attribute.modifiers()) {
                var m = new LinkedHashMap<String, Object>();
                m.put(FIELD_KEY, modifier.key());
                m.put(FIELD_AMOUNT, modifier.amount());
                m.put(FIELD_OPERATION, modifier.operation());
                m.put(FIELD_SLOT_GROUP, modifier.slotGroup() == null ? "any" : modifier.slotGroup());
                encodedModifiers.add(m);
            }
            var a = new LinkedHashMap<String, Object>();
            a.put(FIELD_KEY, attribute.key());
            a.put(FIELD_BASE, attribute.base());
            a.put(FIELD_MODIFIERS, encodedModifiers);
            encodedAttributes.add(a);
        }
        var root = new LinkedHashMap<String, Object>();
        root.put(FIELD_FORMAT, FORMAT);
        root.put(FIELD_CAPTURED_AT, capturedAt.toString());
        root.put(FIELD_ATTRIBUTES, encodedAttributes);
        return root;
    }

    /**
     * Tolerant decode. Never throws. Entries that cannot be salvaged are dropped with
     * a warning; {@link DecodedPayload#rawEntries()} keeps the raw count so the caller
     * can tell "empty snapshot" apart from "everything was garbage".
     */
    static DecodedPayload decode(Map<String, ?> raw) {
        var warnings = new ArrayList<String>();
        String capturedAt = string(raw.get(FIELD_CAPTURED_AT));

        Object formatNode = raw.get(FIELD_FORMAT);
        Integer format = integer(formatNode);
        if (formatNode == null) {
            warnings.add("missing 'format'; assuming format " + FORMAT);
        } else if (format == null) {
            warnings.add("'format' is not an integer (" + formatNode + "); decoding best-effort");
        } else if (format < 1) {
            warnings.add("unsupported format " + format + " (< 1); decoding best-effort");
        } else if (format > FORMAT) {
            warnings.add("payload format " + format + " is newer than supported format " + FORMAT + "; decoding best-effort");
        }

        Object attributesNode = raw.get(FIELD_ATTRIBUTES);
        if (attributesNode == null) {
            warnings.add("missing 'attributes'; treating payload as an empty snapshot");
            return new DecodedPayload(capturedAt, List.of(), warnings, 0);
        }
        if (!(attributesNode instanceof List<?> list)) {
            warnings.add("'attributes' is not a list; payload is structurally unusable");
            return new DecodedPayload(capturedAt, List.of(), warnings, -1);
        }

        int rawEntries = list.size();
        var byKey = new LinkedHashMap<String, CapturedAttribute>(); // attribute keys are unique; last wins
        for (int i = 0; i < rawEntries; i++) {
            Object element = list.get(i);
            if (!(element instanceof Map<?, ?> entry)) {
                warnings.add("attributes[" + i + "] is not an object; skipped");
                continue;
            }
            CapturedAttribute decoded = decodeAttribute(entry, i, warnings);
            if (decoded != null) {
                byKey.put(decoded.key(), decoded);
            }
        }
        if (byKey.size() != rawEntries) {
            // not an error — could be drops or duplicate keys; either way the count line stays honest
            warnings.add("attributes: kept " + byKey.size() + " of " + rawEntries + " entries (duplicates resolve last-wins)");
        }
        return new DecodedPayload(capturedAt, List.copyOf(byKey.values()), warnings, rawEntries);
    }

    /**
     * Pre-restore structural gate. Rejects payloads that would otherwise be applied
     * blindly (not an object, no usable format, attributes not a list, or a payload
     * where every single entry was garbage). Per-entry problems are NOT fatal here —
     * they are the caller's skip-with-warning business.
     */
    static ValidationResult validate(Map<String, ?> raw) {
        if (raw == null) {
            return ValidationResult.invalid("payload must be a JSON object, got null");
        }
        Integer format = integer(raw.get(FIELD_FORMAT));
        if (format == null || format < 1) {
            Object formatNode = raw.get(FIELD_FORMAT);
            return ValidationResult.invalid("'format' must be an integer >= 1, got " + (formatNode == null ? "missing" : formatNode));
        }
        Object attributesNode = raw.get(FIELD_ATTRIBUTES);
        if (!(attributesNode instanceof List<?> list)) {
            return ValidationResult.invalid("'attributes' must be a list, got " + (attributesNode == null ? "missing" : attributesNode.getClass().getSimpleName()));
        }
        if (list.isEmpty()) {
            return ValidationResult.ok(); // an empty snapshot is legal: nothing to restore
        }
        DecodedPayload decoded = decode(raw);
        if (decoded.attributes().isEmpty()) {
            String reason = decoded.warnings().isEmpty() ? "no details" : String.join("; ", decoded.warnings());
            return ValidationResult.invalid("none of the " + list.size() + " attribute entries is usable (" + reason + ")");
        }
        return ValidationResult.ok();
    }

    private static CapturedAttribute decodeAttribute(Map<?, ?> entry, int index, List<String> warnings) {
        String key = string(entry.get(FIELD_KEY));
        if (!AttributeKeys.isValid(key)) {
            warnings.add("attributes[" + index + "] has a missing or invalid key ('" + key + "'); skipped");
            return null;
        }
        Double base = number(entry.get(FIELD_BASE));
        if (base == null || !Double.isFinite(base)) {
            warnings.add("attribute '" + key + "' has a missing or non-finite base; skipped");
            return null;
        }
        var modifiers = new ArrayList<CapturedModifier>();
        Object modifiersNode = entry.get(FIELD_MODIFIERS);
        if (modifiersNode instanceof List<?> list) {
            for (int m = 0; m < list.size(); m++) {
                Object element = list.get(m);
                if (!(element instanceof Map<?, ?> modifier)) {
                    warnings.add("attribute '" + key + "' modifier " + m + " is not an object; skipped");
                    continue;
                }
                CapturedModifier decoded = decodeModifier(modifier, key, m, warnings);
                if (decoded != null) {
                    modifiers.add(decoded);
                }
            }
        } else if (modifiersNode != null) {
            warnings.add("attribute '" + key + "' modifiers is not a list; ignoring modifiers");
        }
        // duplicate modifier keys: last write wins, matching how the map below behaves for attributes
        var byKey = new LinkedHashMap<String, CapturedModifier>();
        for (CapturedModifier modifier : modifiers) {
            byKey.put(modifier.key(), modifier);
        }
        if (byKey.size() != modifiers.size()) {
            warnings.add("attribute '" + key + "' carried duplicate modifier keys; kept the last of each");
        }
        return new CapturedAttribute(key, base, List.copyOf(byKey.values()));
    }

    private static CapturedModifier decodeModifier(Map<?, ?> modifier, String attributeKey, int index, List<String> warnings) {
        String key = string(modifier.get(FIELD_KEY));
        if (!AttributeKeys.isValid(key)) {
            warnings.add("attribute '" + attributeKey + "' modifier " + index + " has a missing or invalid key ('" + key + "'); skipped");
            return null;
        }
        Double amount = number(modifier.get(FIELD_AMOUNT));
        if (amount == null || !Double.isFinite(amount)) {
            warnings.add("modifier '" + key + "' has a missing or non-finite amount; skipped");
            return null;
        }
        String operation = string(modifier.get(FIELD_OPERATION));
        var resolved = OperationCodec.canonical(operation);
        if (resolved.isEmpty()) {
            warnings.add("modifier '" + key + "' has unknown operation '" + operation + "'; skipped");
            return null;
        }
        String slotGroup = string(modifier.get(FIELD_SLOT_GROUP));
        if (slotGroup == null || slotGroup.isBlank()) {
            slotGroup = "any"; // equipment-based slot fidelity is not carried by instances; see the module javadoc
        }
        return new CapturedModifier(key, amount, resolved.get(), slotGroup);
    }

    private static Integer integer(Object node) {
        if (!(node instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        // reject fractional formats like 1.5: a format marker with a fraction is not a version
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private static Double number(Object node) {
        return node instanceof Number number ? number.doubleValue() : null;
    }

    private static String string(Object node) {
        return node instanceof String string && !string.isBlank() ? string : null;
    }
}
