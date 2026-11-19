package lol.candyriya.continuum.paper.modules.attributes;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.Attributable;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * The bukkit-facing restore half. Attribute-key driven: every captured entry is
 * looked up on this server by its namespaced key; unknown attributes (a modded
 * attribute captured on another backend) are skipped with a warning and counted —
 * they never fail the restore.
 *
 * <p>Order of operations per attribute: set the base value first, then the
 * remove-then-add modifier cycle (replace, never accumulate — see
 * {@link ModifierReconciler}). Verification runs afterwards over everything the
 * restore actually applied: base values are re-read and compared with an absolute
 * epsilon of {@link RestoreVerifier#EPSILON}; mismatches are collected as
 * {@link RestoreVerifier.Drift} entries for honest reporting. Not applicable and
 * unknown-on-target attributes are not drift — they were never applied.</p>
 */
final class RestoreEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestoreEngine.class);

    /**
     * @param attributesApplied       entries applied to a live instance
     * @param skippedUnknownAttribute entries whose key this server does not know (modded attributes)
     * @param skippedNotApplicable    entries whose attribute this entity has no instance of
     * @param modifiersRemoved        snapshot-keyed modifiers removed before re-adding
     * @param modifiersAdded          snapshot modifiers re-added
     * @param modifiersSkipped        snapshot modifiers not applied (unsupported operation)
     * @param drift                   base values that did not come back within epsilon
     * @param warnings                everything skipped or degraded, human-readable
     */
    record RestoreOutcome(int attributesApplied, int skippedUnknownAttribute, int skippedNotApplicable,
                          int modifiersRemoved, int modifiersAdded, int modifiersSkipped,
                          List<RestoreVerifier.Drift> drift, List<String> warnings) {

        RestoreOutcome {
            drift = List.copyOf(drift);
            warnings = List.copyOf(warnings);
        }
    }

    private RestoreEngine() {
    }

    static RestoreOutcome restore(Attributable target, DecodedPayload decoded, AttributeCatalog catalog) {
        var warnings = new ArrayList<>(decoded.warnings());
        var applied = new ArrayList<CapturedAttribute>();
        int skippedUnknown = 0;
        int skippedNotApplicable = 0;
        int modifiersRemoved = 0;
        int modifiersAdded = 0;
        int modifiersSkipped = 0;

        for (CapturedAttribute entry : decoded.attributes()) {
            Attribute attribute = catalog.byKey(entry.key());
            if (attribute == null) {
                skippedUnknown++;
                warnings.add("attribute '" + entry.key() + "' is not known on this server; skipped");
                continue;
            }
            AttributeInstance instance = target.getAttribute(attribute);
            if (instance == null) {
                skippedNotApplicable++;
                warnings.add("attribute '" + entry.key() + "' is not applicable to this entity; skipped");
                continue;
            }

            instance.setBaseValue(entry.base());

            ModifierPlan plan = ModifierReconciler.plan(entry.modifiers(), existingModifiers(instance, warnings));
            for (String key : plan.keysToRemove()) {
                // keys in the plan are valid by construction (they came from the snapshot)
                instance.removeModifier(NamespacedKey.fromString(key));
                modifiersRemoved++;
            }
            for (CapturedModifier modifier : plan.modifiersToAdd()) {
                AttributeModifier.Operation operation = resolveOperation(modifier.operation());
                if (operation == null) {
                    // decode already drops these; belt and suspenders, still no throw
                    modifiersSkipped++;
                    warnings.add("modifier '" + modifier.key() + "' has unsupported operation '" + modifier.operation() + "'; skipped");
                    continue;
                }
                instance.addModifier(new AttributeModifier(
                        NamespacedKey.fromString(modifier.key()),
                        modifier.amount(),
                        operation,
                        slotGroupOf(modifier.slotGroup(), modifier.key(), warnings)));
                modifiersAdded++;
            }
            applied.add(entry);
        }

        // verification pass: re-read what we applied, drift honestly — never silently
        var reRead = new HashMap<String, OptionalDouble>();
        for (CapturedAttribute entry : applied) {
            Attribute attribute = catalog.byKey(entry.key());
            AttributeInstance instance = attribute == null ? null : target.getAttribute(attribute);
            reRead.put(entry.key(), instance == null
                    ? OptionalDouble.empty()
                    : OptionalDouble.of(instance.getBaseValue()));
        }
        List<RestoreVerifier.Drift> drift = RestoreVerifier.verify(applied, reRead::get);

        LOGGER.debug("attributes restore: applied={} modifiersAdded={} modifiersRemoved={} drift={}",
                applied.size(), modifiersAdded, modifiersRemoved, drift.size());
        return new RestoreOutcome(applied.size(), skippedUnknown, skippedNotApplicable,
                modifiersRemoved, modifiersAdded, modifiersSkipped, drift, warnings);
    }

    private static AttributeModifier.Operation resolveOperation(String name) {
        return OperationCodec.canonical(name)
                .map(canonical -> switch (canonical) {
                    case OperationCodec.ADD_NUMBER -> AttributeModifier.Operation.ADD_NUMBER;
                    case OperationCodec.ADD_SCALAR -> AttributeModifier.Operation.ADD_SCALAR;
                    case OperationCodec.MULTIPLY_SCALAR_1 -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                    default -> null;
                })
                .orElse(null);
    }

    private static List<CapturedModifier> existingModifiers(AttributeInstance instance, List<String> warnings) {
        var existing = new ArrayList<CapturedModifier>();
        for (AttributeModifier modifier : instance.getModifiers()) {
            try {
                double amount = modifier.getAmount();
                if (!Double.isFinite(amount)) {
                    continue; // unreadable; we simply won't plan a removal for it
                }
                existing.add(new CapturedModifier(
                        modifier.getKey().asString(),
                        amount,
                        modifier.getOperation().name(),
                        modifier.getSlotGroup() != null ? modifier.getSlotGroup().toString() : null));
            } catch (RuntimeException e) {
                // an unreadable existing modifier must not block the restore of the others;
                // plan-less removal is safe (we only remove keys we plan for)
                warnings.add("could not read existing modifier: " + e);
            }
        }
        return existing;
    }

    private static EquipmentSlotGroup slotGroupOf(String slotGroup, String modifierKey, List<String> warnings) {
        if (slotGroup == null) {
            return EquipmentSlotGroup.ANY;
        }
        return switch (slotGroup.toLowerCase(Locale.ROOT)) {
            case "any" -> EquipmentSlotGroup.ANY;
            case "mainhand" -> EquipmentSlotGroup.MAINHAND;
            case "offhand" -> EquipmentSlotGroup.OFFHAND;
            case "hand" -> EquipmentSlotGroup.HAND;
            case "feet" -> EquipmentSlotGroup.FEET;
            case "legs" -> EquipmentSlotGroup.LEGS;
            case "chest" -> EquipmentSlotGroup.CHEST;
            case "head" -> EquipmentSlotGroup.HEAD;
            case "armor" -> EquipmentSlotGroup.ARMOR;
            case "body" -> EquipmentSlotGroup.BODY;
            case "saddle" -> EquipmentSlotGroup.SADDLE;
            default -> {
                warnings.add("modifier '" + modifierKey + "' has unknown slotGroup '" + slotGroup + "'; applying as 'any'");
                yield EquipmentSlotGroup.ANY;
            }
        };
    }
}
