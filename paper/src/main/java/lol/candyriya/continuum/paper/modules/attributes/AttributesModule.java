package lol.candyriya.continuum.paper.modules.attributes;

import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Synchronizes a player's <b>actual</b> attribute layout across backends — base
 * values and attribute modifiers with their namespaced keys — instead of the
 * effective value, so equipment gear and plugin-granted buffs ride along the same
 * pipes as vanilla state.
 *
 * <p><b>Coverage strategy (how we enumerate).</b> Every attribute known to the
 * server is iterated via {@code Registry.ATTRIBUTE} — a data-driven registry, so
 * vanilla attributes (max health, movement speed, attack speed/damage, armor,
 * armor toughness, knockback resistance, luck, reach, block/entity interaction
 * range, jump strength, gravity, safe fall distance, fall damage multiplier, and
 * the rest) and any modded or datapack-added attributes come along for free. For
 * each attribute we ask the entity for its instance; a {@code null} instance means
 * the attribute is not applicable to this entity (players have no
 * {@code spawn_reinforcements}) and the attribute is skipped, counted and never an
 * error. Attributes that throw while being read are isolated per-attribute — they
 * are logged and counted, the rest of the capture proceeds.</p>
 *
 * <p><b>Payload format ({@code version() = 1}).</b> The module's payload
 * {@code T} is a plain {@code Map<String, Object>} tree, so the wire format is
 * byte-identical to what the module builds — map keys are not renamed by any
 * property naming strategy. The format is:</p>
 *
 * <pre>{
 *   "format": 1,
 *   "capturedAt": "2026-11-19T12:34:56.789Z",
 *   "attributes": [
 *     { "key": "minecraft:max_health", "base": 20.0,
 *       "modifiers": [
 *         { "key": "someplugin:headstart", "amount": 2.0,
 *           "operation": "ADD_NUMBER", "slotGroup": "any" } ] } ] }</pre>
 *
 * <p>Deterministic by construction: attributes are emitted in registry order
 * (stable for a given server build) and modifiers are sorted by
 * (key, operation, amount, slotGroup). Unknown operation names are skipped with a
 * warning at decode time — never an exception. Formats other than {@code 1}
 * decode best-effort with a warning; a structurally broken payload is rejected by
 * {@link #validate} and refused by {@link #restore}.</p>
 *
 * <p><b>Modifier restore protocol (dedupe).</b> Every modifier key present in the
 * snapshot is removed from the instance first (when present) and then re-added —
 * replace, never accumulate, and applying the same payload twice is a no-op.
 * Instance modifiers whose keys are absent from the snapshot are never touched:
 * this preserves gear/potion-sourced modifiers and anything other modules own.
 * A blanket sweep of the {@code continuum} namespace was considered and rejected —
 * it would delete a modifier legitimately applied to the same instance by another
 * module. Restore is <em>snapshot-key driven</em>: unknown attribute on the target
 * server (a modded attribute) is skipped with a warning and counted; it never fails
 * the restore.</p>
 *
 * <p><b>Honest fidelity limits.</b></p>
 * <ul>
 *   <li>Equipment/item-sourced attribute modifiers are not entity state — they are
 *       resolved from equipped items per tick and never appear in
 *       {@code AttributeInstance#getModifiers()}. Items restore through the item
 *       pipeline, not this module.</li>
 *   <li>{@code getModifiers()} reports whatever slot group the server attaches to
 *       the instance modifier. On current 26.3 instances every modifier reports
 *       {@code EquipmentSlotGroup.ANY} — equipment slot fidelity is therefore not
 *       guaranteed by this module, and dedupe is key-driven rather than slot-driven.
 *       The stored {@code slotGroup} is whatever the API hands us; on apply, unknown
 *       names degrade to {@code any} with a warning.</li>
 *   <li>Modifiers added via {@code addTransientModifier} are not persisted by the
 *       server. If the API surfaces them in {@code getModifiers()} we capture them
 *       and re-add them as persistent modifiers on restore (sync makes them
 *       permanent by definition — there is no transient channel to keep).</li>
 *   <li>Attribute <em>effective</em> values are not verified — they depend on items,
 *       effects and other modules' timing. Only base values are re-read and checked
 *       after restore, within an absolute epsilon of {@code 1e-6}.</li>
 * </ul>
 *
 * <p><b>Restore order.</b> Attributes must land before vitals (max health has to be
 * raised before current health is written). Restoring order: dependencies first,
 * then ascending {@code priority()}, ties broken by id. This module declares
 * {@code priority() = 90} — strictly below {@link SyncModule#DEFAULT_PRIORITY}
 * (100, where vitals and peers default) — so even a vitals module that forgets
 * its {@code dependencies()} still restores after attributes. Vitals is expected
 * to declare {@code continuum.attributes} in its dependencies; belt and suspenders,
 * both mechanisms agree.</p>
 *
 * <p><b>Threading.</b> {@link #capture}, {@link #restore} and {@link #validate} are
 * invoked on the player's scheduler thread (Paper main thread / Folia entity region
 * thread). Registry access is a read of an immutable-after-boot registry, performed
 * inline — no thread hops, no blocking I/O, no cross-thread state. {@link #id()},
 * {@link #version()}, {@link #priority()} and {@link #dependencies()} are constants
 * and safe to call from any thread during restore planning.</p>
 *
 * <p><b>Failure isolation and reporting.</b> Per-attribute and per-modifier problems
 * never fail the restore — they are skipped with a warning and counted in the
 * module's log summary (applied / unknown / not applicable / modifiers removed,
 * re-added, skipped / drift count). {@code SyncModule} carries no warning channel
 * from module to pipeline, so skipped counts and drift lines are logged at WARN
 * with the values involved; nothing is silently dropped. A restore payload in
 * which <em>every</em> attribute entry is unusable is refused (exceptional
 * completion) — restoring it would leave target defaults in place while reporting
 * success, which is exactly the silent data loss this module exists to prevent.
 * Unexpected throwables complete the stage exceptionally ({@code RESTORE_FAILED}),
 * where the pipeline's failure policy isolates them.</p>
 *
 * <p><b>verification.</b> After all entries are applied, each applied attribute is
 * re-read and its base value compared against the captured one (absolute epsilon
 * {@code 1e-6}). Drift is logged — attribute, captured value, actual value — and
 * counted in the summary. Unknown-on-target and not-applicable attributes are not
 * drift; they were never applied.</p>
 */
public final class AttributesModule implements SyncModule<Player, Map<String, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttributesModule.class);

    /** Lower than {@link SyncModule#DEFAULT_PRIORITY} so attributes restore before vitals. */
    public static final int PRIORITY = AttributesContract.PRIORITY;

    private final AttributeCatalog catalog;

    /** Creates the module against the live attribute registry. */
    public AttributesModule() {
        this(new RegistryAttributeCatalog());
    }

    /** Test seam: a catalog different from the live registry. */
    AttributesModule(AttributeCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String id() {
        return AttributesContract.ID;
    }

    @Override
    public int version() {
        return AttributesContract.SCHEMA_VERSION;
    }

    @Override
    public int priority() {
        return AttributesContract.PRIORITY;
    }

    @Override
    public List<String> dependencies() {
        // attributes depend on nothing; vitals is expected to depend on us.
        // depending on vitals here would be a cycle waiting to happen.
        return AttributesContract.DEPENDENCIES;
    }

    @Override
    public CompletionStage<Map<String, Object>> capture(Player player) {
        try {
            CaptureEngine.CaptureResult result = CaptureEngine.capture(player, catalog);
            if (result.attributes().isEmpty() && result.errors() > 0) {
                // reading blew up everywhere — persisting that as "no attributes" would
                // restore wrong state later; fail visibly instead
                return CompletableFuture.failedFuture(new AttributesModuleException(
                        "could not capture a single attribute (" + result.errors() + " read failure(s))"));
            }
            LOGGER.info("attributes: captured {} attribute(s), {} modifier(s); {} not applicable, {} read error(s)",
                    result.attributes().size(), result.modifiers(), result.notApplicable(), result.errors());
            return CompletableFuture.completedFuture(AttributesPayloadCodec.encode(result.attributes(), Instant.now()));
        } catch (RuntimeException e) {
            LOGGER.warn("attributes: capture failed: {}", e.toString());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, Map<String, Object> data) {
        try {
            return CompletableFuture.completedFuture(AttributesPayloadCodec.validate(data));
        } catch (RuntimeException e) {
            LOGGER.warn("attributes: validation errored: {}", e.toString());
            return CompletableFuture.completedFuture(ValidationResult.invalid("validation crashed: " + e));
        }
    }

    @Override
    public CompletionStage<Void> restore(Player player, Map<String, Object> data) {
        if (data == null) {
            return CompletableFuture.failedFuture(new AttributesModuleException("payload must not be null"));
        }
        try {
            DecodedPayload decoded = AttributesPayloadCodec.decode(data);
            if (decoded.attributes().isEmpty() && decoded.rawEntries() != 0) {
                // every entry was unusable (or the attributes node was structurally broken);
                // applying nothing would leave target defaults in place while looking like success
                return CompletableFuture.failedFuture(new AttributesModuleException(
                        "payload carried " + decoded.rawEntries() + " attribute entries, none usable ("
                                + String.join("; ", decoded.warnings()) + ")"));
            }

            RestoreEngine.RestoreOutcome outcome = RestoreEngine.restore(player, decoded, catalog);
            for (String warning : outcome.warnings()) {
                LOGGER.warn("attributes restore: {}", warning);
            }
            for (RestoreVerifier.Drift drift : outcome.drift()) {
                LOGGER.warn("attributes restore: '{}' drifted (captured {}, now {})",
                        drift.attributeKey(), drift.expected(),
                        Double.isNaN(drift.actual()) ? "unavailable" : Double.toString(drift.actual()));
            }
            LOGGER.info("attributes restore: {} applied, {} unknown on this server, {} not applicable; "
                            + "modifiers: {} removed, {} re-added, {} skipped; drift: {}",
                    outcome.attributesApplied(), outcome.skippedUnknownAttribute(), outcome.skippedNotApplicable(),
                    outcome.modifiersRemoved(), outcome.modifiersAdded(), outcome.modifiersSkipped(),
                    outcome.drift().size());
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            LOGGER.warn("attributes restore: failed: {}", e.toString());
            return CompletableFuture.failedFuture(e);
        }
    }
}
