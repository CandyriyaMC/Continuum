package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wires the pure layers together the way a restore does: encode a capture → decode
 * it → reconcile against what a live instance holds → verify. A simulated instance
 * is just a list of modifiers here — the bukkit calls are the thin shell around this.
 */
class RestorePipelineTest {

    private static final Instant AT = Instant.parse("2026-11-19T12:34:56.789Z");

    private static CapturedAttribute maxHealth(double base, CapturedModifier... modifiers) {
        return new CapturedAttribute("minecraft:max_health", base, List.of(modifiers));
    }

    private static CapturedModifier modifier(String key, double amount) {
        return new CapturedModifier(key, amount, "ADD_NUMBER", "any");
    }

    /** Apply a plan to the simulated instance and return the resulting modifier keys. */
    private static List<String> applyPlan(List<CapturedModifier> instance, ModifierPlan plan) {
        var result = new ArrayList<>(instance);
        for (String key : plan.keysToRemove()) {
            result.removeIf(modifier -> modifier.key().equals(key));
        }
        result.addAll(plan.modifiersToAdd());
        return result.stream().map(CapturedModifier::key).toList();
    }

    @Test
    void captureEncodeDecodeRestorePlanProducesExpectedState() {
        var captured = List.of(
                maxHealth(20.0, modifier("continuum:headstart", 2.0)),
                new CapturedAttribute("minecraft:luck", 3.0, List.of()));
        var wire = AttributesPayloadCodec.encode(captured, AT);
        var decoded = AttributesPayloadCodec.decode(wire);

        // a live instance carrying one foreign modifier and one stale synced modifier
        var instance = new ArrayList<>(List.of(
                modifier("someplugin:persistent", 4.0),
                modifier("continuum:headstart", 99.0))); // stale: from an earlier sync, different amount

        ModifierPlan plan = ModifierReconciler.plan(decoded.attributes().getFirst().modifiers(), instance);
        var finalKeys = applyPlan(instance, plan);

        assertEquals(List.of("someplugin:persistent", "continuum:headstart"), finalKeys);
        // the snapshot version of the modifier wins, not the stale one
        assertEquals(2.0, plan.modifiersToAdd().getFirst().amount());
    }

    @Test
    void doubleRestoreThroughTheCodecNeverAccumulates() {
        var captured = List.of(maxHealth(20.0,
                modifier("continuum:headstart", 2.0),
                new CapturedModifier("continuum:buff", 0.25, "MULTIPLY_SCALAR_1", "mainhand")));
        var wire = AttributesPayloadCodec.encode(captured, AT);

        // a live instance keyed by modifier key, like the bukkit one; two full cycles
        var instance = new java.util.LinkedHashMap<String, CapturedModifier>();
        for (int cycle = 0; cycle < 2; cycle++) {
            var decoded = AttributesPayloadCodec.decode(wire);
            var snapshotMods = decoded.attributes().getFirst().modifiers();
            ModifierPlan plan = ModifierReconciler.plan(snapshotMods, new ArrayList<>(instance.values()));
            for (String key : plan.keysToRemove()) {
                instance.remove(key);
            }
            for (CapturedModifier modifier : plan.modifiersToAdd()) {
                instance.put(modifier.key(), modifier);
            }
        }

        assertEquals(2, instance.size(), "two restores must leave exactly the snapshot's modifiers");
        assertEquals(2.0, instance.get("continuum:headstart").amount());
        assertEquals(0.25, instance.get("continuum:buff").amount());
    }

    @Test
    void verificationOfAWellBehavedRestoreIsClean() {
        var captured = List.of(maxHealth(20.0), new CapturedAttribute("minecraft:luck", 3.0, List.of()));
        var wire = AttributesPayloadCodec.encode(captured, AT);
        var decoded = AttributesPayloadCodec.decode(wire);

        // simulate a perfect server: re-read returns exactly what we set
        var drift = RestoreVerifier.verify(decoded.attributes(), key -> OptionalDouble.of(
                decoded.attributes().stream().filter(a -> a.key().equals(key)).findFirst().orElseThrow().base()));

        assertTrue(drift.isEmpty());
    }

    @Test
    void verificationCatchesTheOneThatDrifted() {
        var captured = List.of(maxHealth(20.0));
        var wire = AttributesPayloadCodec.encode(captured, AT);
        var decoded = AttributesPayloadCodec.decode(wire);

        var drift = RestoreVerifier.verify(decoded.attributes(), key -> OptionalDouble.of(19.5));

        assertEquals(1, drift.size());
        assertEquals("minecraft:max_health", drift.getFirst().attributeKey());
    }
}
