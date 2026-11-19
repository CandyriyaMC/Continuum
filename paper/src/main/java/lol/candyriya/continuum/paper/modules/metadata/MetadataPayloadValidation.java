package lol.candyriya.continuum.paper.modules.metadata;

import lol.candyriya.continuum.api.module.ValidationResult;

/**
 * Structural gate for {@code continuum.metadata} payloads, run before restore.
 * Checks shape and budget only — it deliberately does not decide policy the
 * platform will enforce itself (e.g. which components a server likes to see).
 */
public final class MetadataPayloadValidation {

    private static final MetadataComponentCodec PRODUCTION = new MetadataComponentCodec();

    private MetadataPayloadValidation() {
        // static access only
    }

    /**
     * @return {@link ValidationResult#ok()} when the payload is safe to hand to a live player,
     *         otherwise an invalid result carrying the first offending key
     */
    public static ValidationResult validate(MetadataPayload data) {
        return validate(data, PRODUCTION);
    }

    /** Package-private: tests inject the in-memory component codec. */
    static ValidationResult validate(MetadataPayload data, MetadataComponentCodec codec) {
        if (data == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (data.format() != MetadataPayload.FORMAT) {
            return ValidationResult.invalid("unsupported metadata payload format " + data.format()
                    + " (this build writes and restores " + MetadataPayload.FORMAT + ")");
        }
        ValidationResult checked = checkComponent(data.displayName(), wireKey(MetadataEntry.DISPLAY_NAME), codec);
        if (checked != null) {
            return checked;
        }
        checked = checkComponent(data.playerListName(), wireKey(MetadataEntry.PLAYER_LIST_NAME), codec);
        return checked != null ? checked : ValidationResult.ok();
    }

    private static ValidationResult checkComponent(String stored, String wireKey, MetadataComponentCodec codec) {
        if (stored == null) {
            // absence is a perfectly valid state — the mirror resets the target to default
            return null;
        }
        if (stored.isBlank()) {
            return ValidationResult.invalid(wireKey + " is blank; expected gson component JSON or null");
        }
        if (stored.length() > MetadataPayload.MAX_COMPONENT_CHARS) {
            return ValidationResult.invalid(wireKey + " is " + stored.length()
                    + " chars, over the " + MetadataPayload.MAX_COMPONENT_CHARS + "-char budget");
        }
        try {
            codec.decode(stored, wireKey);
            return null;
        } catch (IllegalStateException e) {
            return ValidationResult.invalid(wireKey + " is not restorable: " + e.getMessage());
        }
    }

    private static String wireKey(MetadataEntry entry) {
        String key = entry.wireKey();
        if (key == null) {
            throw new IllegalStateException(entry + " is excluded from sync; internal wiring bug");
        }
        return key;
    }
}
