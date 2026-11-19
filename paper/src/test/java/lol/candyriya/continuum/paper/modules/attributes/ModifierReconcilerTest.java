package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replacement (never accumulation) math: snapshot keys are removed from the
 * instance and re-added; foreign keys are never in the plan.
 */
class ModifierReconcilerTest {

    private static CapturedModifier modifier(String key) {
        return new CapturedModifier(key, 1.0, "ADD_NUMBER", "any");
    }

    @Test
    void addsEverythingAndRemovesOnlyWhatTheInstanceHas() {
        var plan = ModifierReconciler.plan(
                List.of(modifier("continuum:a"), modifier("continuum:b"), modifier("otherplugin:c")),
                List.of(modifier("continuum:a"), modifier("otherplugin:keep")));

        assertEquals(List.of("continuum:a"), plan.keysToRemove());
        assertEquals(List.of("continuum:a", "continuum:b", "otherplugin:c"),
                plan.modifiersToAdd().stream().map(CapturedModifier::key).toList());
    }

    @Test
    void foreignInstanceModifiersAreNeverPlannedForRemoval() {
        var plan = ModifierReconciler.plan(
                List.of(modifier("continuum:a")),
                List.of(modifier("otherplugin:gear"), modifier("continuum:a")));

        assertEquals(List.of("continuum:a"), plan.keysToRemove());
    }

    @Test
    void snapshotModifierMatchingAForeignKeyStillReplacesItByKey() {
        // snapshot-key driven: the snapshot captured this exact foreign modifier,
        // so restore replaces it (amount 1.0 snapshot vs 5.0 live), same protocol
        var plan = ModifierReconciler.plan(
                List.of(new CapturedModifier("otherplugin:x", 1.0, "ADD_NUMBER", "any")),
                List.of(new CapturedModifier("otherplugin:x", 5.0, "ADD_NUMBER", "any")));

        assertEquals(List.of("otherplugin:x"), plan.keysToRemove());
        assertEquals(1.0, plan.modifiersToAdd().getFirst().amount());
    }

    @Test
    void emptySnapshotClearsNothing() {
        var plan = ModifierReconciler.plan(List.of(), List.of(modifier("continuum:a")));
        assertTrue(plan.keysToRemove().isEmpty());
        assertTrue(plan.modifiersToAdd().isEmpty());
    }

    @Test
    void removeKeysAreDeduplicatedEvenIfTheSnapshotSlipsDuplicates() {
        // decode resolves duplicates last-wins; this is belt and suspenders at the planner level
        var plan = ModifierReconciler.plan(
                List.of(modifier("continuum:a"), modifier("continuum:a")),
                List.of(modifier("continuum:a")));

        assertEquals(List.of("continuum:a"), plan.keysToRemove());
    }
}
