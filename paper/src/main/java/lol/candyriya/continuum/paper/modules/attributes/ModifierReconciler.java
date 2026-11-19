package lol.candyriya.continuum.paper.modules.attributes;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Pure diff math for the restore path. The rules, in order:
 *
 * <ol>
 *   <li>Every modifier key present in the snapshot is removed from the instance
 *       first (if it exists) and then re-added. Applying the same payload twice
 *       can never stack modifiers — the second restore sees the same keys and
 *       performs the same remove-then-add cycle.</li>
 *   <li>Instance modifiers whose keys are absent from the snapshot are never
 *       touched. That includes item/potion-sourced modifiers living in the
 *       instance, other plugins' modifiers and anything another module owns under
 *       the {@code continuum} namespace. A blanket namespace sweep was considered
 *       and rejected: it would delete a modifier another Continuum module had
 *       legitimately applied to the same instance.</li>
 *   <li>Duplicate keys inside the snapshot itself were already resolved at decode
 *       time (last wins), so {@code captured} here carries no duplicate keys.</li>
 * </ol>
 */
final class ModifierReconciler {

    private ModifierReconciler() {
    }

    static ModifierPlan plan(List<CapturedModifier> captured, List<CapturedModifier> existing) {
        var existingKeys = new LinkedHashSet<String>(existing.size());
        for (CapturedModifier modifier : existing) {
            existingKeys.add(modifier.key());
        }
        var keysToRemove = captured.stream()
                .map(CapturedModifier::key)
                .filter(existingKeys::contains)
                .distinct()
                .toList();
        return new ModifierPlan(keysToRemove, List.copyOf(captured));
    }
}
