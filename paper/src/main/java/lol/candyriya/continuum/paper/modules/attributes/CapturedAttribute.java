package lol.candyriya.continuum.paper.modules.attributes;

import java.util.Comparator;
import java.util.List;

/**
 * One captured attribute: its base value and the modifiers present on the
 * instance at capture time. {@code modifiers} is sorted canonically
 * (key, operation, amount, slotGroup) so two captures of the same state encode
 * identically.
 */
record CapturedAttribute(String key, double base, List<CapturedModifier> modifiers) {

    /** Canonical modifier order — shared by the codec and the restore path. */
    static final Comparator<CapturedModifier> MODIFIER_ORDER = Comparator
            .comparing(CapturedModifier::key)
            .thenComparing(CapturedModifier::operation)
            .thenComparingDouble(CapturedModifier::amount)
            .thenComparing(modifier -> modifier.slotGroup() == null ? "" : modifier.slotGroup());

    CapturedAttribute {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        if (!Double.isFinite(base)) {
            throw new IllegalArgumentException("base value must be finite, got " + base);
        }
        var sorted = modifiers == null ? new java.util.ArrayList<CapturedModifier>() : new java.util.ArrayList<>(modifiers);
        sorted.sort(MODIFIER_ORDER);
        modifiers = List.copyOf(sorted);
    }
}
