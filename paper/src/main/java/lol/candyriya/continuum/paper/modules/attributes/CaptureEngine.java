package lol.candyriya.continuum.paper.modules.attributes;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.Attributable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The bukkit-facing capture half. Enumerates every attribute the server knows
 * ({@code Registry.ATTRIBUTE} via the catalog — data-driven, so modded attributes are
 * picked up for free), asks the entity for its instance of each, and records what
 * comes back. Attributes the entity does not have an instance of (e.g. a player asked
 * about {@code spawn_reinforcements}) are skipped, counted and otherwise ignored.
 *
 * <p>Per-attribute read failures are isolated: a broken attribute logs a warning and is
 * counted as an error, the rest of the capture proceeds. If nothing at all could be
 * read and at least one error occurred, the capture result carries zero attributes with
 * {@code errors > 0} so the module can fail loudly instead of persisting an empty snapshot.</p>
 */
final class CaptureEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureEngine.class);

    /**
     * @param attributes     captured entries in registry order
     * @param notApplicable  attributes the entity has no instance of (skipped silently)
     * @param errors         attributes that threw while being read (skipped with a warning)
     * @param modifiers      total modifiers captured — for the log line only
     */
    record CaptureResult(List<CapturedAttribute> attributes, int notApplicable, int errors, int modifiers) {

        CaptureResult {
            attributes = List.copyOf(attributes);
        }
    }

    private CaptureEngine() {
    }

    static CaptureResult capture(Attributable target, AttributeCatalog catalog) {
        var attributes = new ArrayList<CapturedAttribute>();
        int notApplicable = 0;
        int errors = 0;
        int modifierCount = 0;

        for (Attribute attribute : catalog.all().toList()) {
            String key = attribute.getKey().asString();
            try {
                AttributeInstance instance = target.getAttribute(attribute);
                if (instance == null) {
                    notApplicable++;
                    continue;
                }
                var modifiers = new ArrayList<CapturedModifier>();
                for (AttributeModifier modifier : instance.getModifiers()) {
                    CapturedModifier captured = toCaptured(modifier, key);
                    if (captured != null) {
                        modifiers.add(captured);
                    }
                }
                modifierCount += modifiers.size();
                attributes.add(new CapturedAttribute(key, instance.getBaseValue(), modifiers));
            } catch (RuntimeException e) {
                // a single broken attribute must not take down the whole capture
                errors++;
                LOGGER.warn("failed to capture attribute '{}': {}", key, e.toString());
            }
        }
        return new CaptureResult(attributes, notApplicable, errors, modifierCount);
    }

    private static CapturedModifier toCaptured(AttributeModifier modifier, String attributeKey) {
        try {
            String key = modifier.getKey().asString();
            double amount = modifier.getAmount();
            if (!Double.isFinite(amount)) {
                LOGGER.warn("attribute '{}': dropping modifier '{}' with non-finite amount {}", attributeKey, key, amount);
                return null;
            }
            String operation = modifier.getOperation().name();
            // getSlotGroup() is whatever this server reports; current 26.3 instances
            // report EquipmentSlotGroup.ANY for everything — stored verbatim, see module javadoc
            String slotGroup = modifier.getSlotGroup() != null ? modifier.getSlotGroup().toString() : null;
            return new CapturedModifier(key, amount, operation, slotGroup == null ? "any" : slotGroup);
        } catch (RuntimeException e) {
            LOGGER.warn("attribute '{}': dropping unreadable modifier: {}", attributeKey, e.toString());
            return null;
        }
    }
}
