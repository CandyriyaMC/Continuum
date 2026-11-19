package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Capture/restore logic for the metadata module. All platform access goes
 * through {@link ProfileOps}, so this class is unit-testable without a
 * server; the component codec is injectable for the same reason.
 *
 * <p>Restore is an <b>unconditional exact mirror</b>: values present in the
 * snapshot are re-applied as stored, values absent are reset to the vanilla
 * default ({@code displayName(null)} / {@code playerListName(null)}). It never
 * merges, never keeps target-side state, and is idempotent — applying the
 * same payload twice converges to the same state. Keys are applied in a
 * fixed order ({@link MetadataEntry#applicable()}), each best-effort: a
 * malformed key is reported and skipped, the rest still applies.
 */
public final class MetadataEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetadataEngine.class);

    private final MetadataComponentCodec codec;

    /** The production engine — gson component JSON via the platform serializer. */
    public MetadataEngine() {
        this.codec = new MetadataComponentCodec();
    }

    /** Tests inject an in-memory component codec. */
    MetadataEngine(MetadataComponentCodec codec) {
        this.codec = codec;
    }

    /**
     * Capture the current custom names into a payload. Components equal to the
     * plain player name normalize to {@code null} ("never customized"); anything
     * else encodes as gson component JSON within the per-key budget.
     *
     * @throws IllegalStateException when a component exceeds the budget — capture must fail loudly, never truncate
     */
    public MetadataPayload capture(ProfileOps ops) {
        Objects.requireNonNull(ops, "ops must not be null");
        String plainName = ops.playerName();
        if (plainName == null || plainName.isBlank()) {
            throw new IllegalStateException("platform returned no player name; refusing to capture");
        }
        String displayName = codec.normalizeForCapture(
                ops.displayName(), plainName, wireKey(MetadataEntry.DISPLAY_NAME));
        String playerListName = codec.normalizeForCapture(
                ops.listName(), plainName, wireKey(MetadataEntry.PLAYER_LIST_NAME));
        return new MetadataPayload(MetadataPayload.FORMAT, displayName, playerListName);
    }

    /**
     * Mirror the stored names onto the live profile. Each key is applied or
     * reset independently; a key whose JSON cannot be decoded is logged,
     * recorded in the report and skipped — the other keys still apply.
     *
     * <p>Restore callers should treat a non-empty {@link RestoreReport#failed()}
     * as an exceptional condition (the stored snapshot is bogus) rather than a
     * soft skip; the module does exactly that.</p>
     */
    public RestoreReport restore(ProfileOps ops, MetadataPayload payload) {
        Objects.requireNonNull(ops, "ops must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        var applied = new java.util.ArrayList<String>();
        var reset = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<String>();

        restoreKey(payload.displayName(), wireKey(MetadataEntry.DISPLAY_NAME),
                component -> ops.setDisplayName(component), applied, reset, failed);
        restoreKey(payload.playerListName(), wireKey(MetadataEntry.PLAYER_LIST_NAME),
                component -> ops.setListName(component), applied, reset, failed);

        return new RestoreReport(applied, reset, failed);
    }

    private void restoreKey(String stored, String wireKey, java.util.function.Consumer<Component> write,
                            List<String> applied, List<String> reset, List<String> failed) {
        if (stored == null) {
            // no custom value captured — the mirror wants the vanilla default on the target
            write.accept(null);
            reset.add(wireKey);
            return;
        }
        if (stored.length() > MetadataPayload.MAX_COMPONENT_CHARS) {
            // belt and braces for callers that skipped validation
            LOGGER.error("restore of {} skipped: {} chars exceeds the {}-char budget", wireKey,
                    stored.length(), MetadataPayload.MAX_COMPONENT_CHARS);
            failed.add(wireKey + " (over budget)");
            return;
        }
        try {
            write.accept(codec.decode(stored, wireKey));
            applied.add(wireKey);
        } catch (IllegalStateException e) {
            // bogus JSON — leave the target's current value alone, report it
            LOGGER.error("restore of {} failed: {}", wireKey, e.getMessage());
            failed.add(wireKey + " (" + e.getMessage() + ")");
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
