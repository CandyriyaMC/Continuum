package lol.candyriya.continuum.paper.modules.vitals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering and value tests for the vitals restore path, driven through a
 * recording {@link VitalsOps} — the production restore executes exactly the
 * same code, only the entity calls are scripted here.
 */
class VitalsApplyOrderTest {

    private static final class RecordingOps implements VitalsOps {
        final List<String> calls = new ArrayList<>();
        double maxHealth = 40.0;
        int maxFireTicks = 20;
        int maxFreezeTicks = 140;

        @Override
        public double maxHealth() {
            calls.add("maxHealth");
            return maxHealth;
        }

        @Override
        public int maxFireTicks() {
            calls.add("maxFireTicks");
            return maxFireTicks;
        }

        @Override
        public int maxFreezeTicks() {
            calls.add("maxFreezeTicks");
            return maxFreezeTicks;
        }

        @Override
        public void setFoodLevel(int level) {
            calls.add("setFoodLevel=" + level);
        }

        @Override
        public void setSaturation(float value) {
            calls.add("setSaturation=" + value);
        }

        @Override
        public void setExhaustion(float value) {
            calls.add("setExhaustion=" + value);
        }

        @Override
        public void setRemainingAir(int ticks) {
            calls.add("setRemainingAir=" + ticks);
        }

        @Override
        public void setFireTicks(int ticks) {
            calls.add("setFireTicks=" + ticks);
        }

        @Override
        public void setFreezeTicks(int ticks) {
            calls.add("setFreezeTicks=" + ticks);
        }

        @Override
        public void setHealth(double health) {
            calls.add("setHealth=" + health);
        }

        @Override
        public void setAbsorptionAmount(double amount) {
            calls.add("setAbsorptionAmount=" + amount);
        }

        @Override
        public void setFallDistance(float distance) {
            calls.add("setFallDistance=" + distance);
        }
    }

    private static VitalsPayload payload() {
        return new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 10, 30, 1.75f);
    }

    @Test
    void restoreAppliesInDocumentedOrder() {
        RecordingOps ops = new RecordingOps();
        VitalsApply.apply(payload(), ops, true);

        List<String> order = ops.calls.stream().map(VitalsApplyOrderTest::methodName).toList();
        assertEquals(List.of(
                        "maxHealth",
                        "setFoodLevel",
                        "setSaturation",
                        "setExhaustion",
                        "setRemainingAir",
                        "maxFireTicks",
                        "setFireTicks",
                        "maxFreezeTicks",
                        "setFreezeTicks",
                        "setHealth",
                        "setAbsorptionAmount",
                        "setFallDistance"),
                order);
    }

    @Test
    void absorptionIsAppliedAfterHealth() {
        RecordingOps ops = new RecordingOps();
        VitalsApply.apply(payload(), ops, true);

        assertTrue(ops.calls.indexOf("setHealth=17.5") > 0);
        assertTrue(ops.calls.indexOf("setAbsorptionAmount=6.0") > ops.calls.indexOf("setHealth=17.5"),
                "absorption must ride on top of the restored health, never the other way around");
    }

    @Test
    void healthIsClampedToEffectiveMaxFromAttributes() {
        RecordingOps ops = new RecordingOps();
        ops.maxHealth = 15.0; // attributes module raised it to something below the stored health
        VitalsApply.apply(payload(), ops, true);

        assertTrue(ops.calls.contains("setHealth=15.0"), ops.calls.toString());
    }

    @Test
    void healthAboveBrokenCeilingFallsBackToVanillaDefault() {
        RecordingOps ops = new RecordingOps();
        ops.maxHealth = 0.0; // attribute cascade broke — must not read as "kill the player"
        VitalsApply.apply(payload(), ops, true);

        assertTrue(ops.calls.contains("setHealth=17.5"), ops.calls.toString());
    }

    @Test
    void saturationIsCappedByRestoredFoodLevel() {
        RecordingOps ops = new RecordingOps();
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 10, 18.0f, 2.5f, 187, 0, 0, 1.75f);
        VitalsApply.apply(payload, ops, true);

        assertTrue(ops.calls.contains("setSaturation=10.0"), ops.calls.toString());
    }

    @Test
    void fireAndFreezeTicksClampToLiveMax() {
        RecordingOps ops = new RecordingOps();
        ops.maxFireTicks = 20;
        ops.maxFreezeTicks = 140;
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 999, 999, 1.75f);
        VitalsApply.apply(payload, ops, true);

        assertTrue(ops.calls.contains("setFireTicks=20"), ops.calls.toString());
        assertTrue(ops.calls.contains("setFreezeTicks=140"), ops.calls.toString());
    }

    @Test
    void fallDistanceIsSkippedWhenNotCaptured() {
        RecordingOps ops = new RecordingOps();
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, null);
        VitalsApply.apply(payload, ops, true);

        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setFallDistance")));
    }

    @Test
    void fallDistanceIsSkippedWhenModuleDisablesIt() {
        RecordingOps ops = new RecordingOps();
        VitalsApply.apply(payload(), ops, false);

        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setFallDistance")));
    }

    @Test
    void applyIsIdempotent() {
        RecordingOps ops = new RecordingOps();
        VitalsApply.apply(payload(), ops, true);
        List<String> first = List.copyOf(ops.calls);
        ops.calls.clear();
        VitalsApply.apply(payload(), ops, true);

        assertEquals(first, ops.calls);
    }

    private static String methodName(String call) {
        int eq = call.indexOf('=');
        return eq < 0 ? call : call.substring(0, eq);
    }
}
