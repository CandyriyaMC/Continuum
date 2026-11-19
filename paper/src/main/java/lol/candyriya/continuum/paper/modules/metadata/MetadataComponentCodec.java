package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

import org.jetbrains.annotations.Nullable;

/**
 * The component codec the module uses. Owns encode/decode/normalize decisions —
 * budgets, error context, the plain-name collapse — while the byte-level
 * (de)serialization lives behind {@link ComponentJson}
 * (production: {@link GsonComponentJson}).
 *
 * <p>All failures are reported as {@link IllegalStateException} with context
 * (wire key, lengths) and without component content — a display name can
 * carry private flair, and logs must not leak it.</p>
 */
public final class MetadataComponentCodec {

    private final ComponentJson json;

    /** The production codec, backed by the platform-provided gson component serializer. */
    public MetadataComponentCodec() {
        this.json = new GsonComponentJson();
    }

    /** Tests inject an in-memory double — the gson serializer is not on the test classpath. */
    MetadataComponentCodec(ComponentJson json) {
        this.json = json;
    }

    /** Serialize a component to its gson component JSON. */
    public String encode(Component component) {
        try {
            return json.toJson(component);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "failed to serialize component (" + safeLength(component) + " chars)", e);
        }
    }

    /**
     * Parse gson component JSON. @throws IllegalStateException when the input is not valid.
     */
    public Component decode(String stored, String wireKey) {
        // decode with the same budget guard used at capture, so a corrupt over-budget string
        // gets rejected here too instead of being parsed
        if (stored.length() > MetadataPayload.MAX_COMPONENT_CHARS) {
            throw new IllegalStateException(wireKey + " is " + stored.length()
                    + " chars, over the " + MetadataPayload.MAX_COMPONENT_CHARS + "-char budget");
        }
        return json.fromJson(stored, wireKey);
    }

    /**
     * Serialize with the per-key budget enforced.
     *
     * @throws IllegalStateException when the encoded form exceeds {@link MetadataPayload#MAX_COMPONENT_CHARS} —
     *         callers must fail loudly, never truncate
     */
    public String encodeLimited(Component component, String wireKey) {
        String encoded = encode(component);
        if (encoded.length() > MetadataPayload.MAX_COMPONENT_CHARS) {
            throw new IllegalStateException(wireKey + " encodes to " + encoded.length()
                    + " chars (cap " + MetadataPayload.MAX_COMPONENT_CHARS + "); refusing to capture");
        }
        return encoded;
    }

    /**
     * Normalize a captured component for storage.
     *
     * <p>{@code null} (unset on the platform) and any component equal to the
     * plain player name both mean "never customized" and normalize to
     * {@code null} — the wire encodes absence as an explicit reset for the
     * target. Everything else encodes as gson component JSON within budget.</p>
     */
    public @Nullable String normalizeForCapture(@Nullable Component captured, String plainName, String wireKey) {
        if (captured == null || captured.equals(Component.text(plainName))) {
            return null;
        }
        return encodeLimited(captured, wireKey);
    }

    private int safeLength(Component component) {
        try {
            return json.toJson(component).length();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
