package lol.candyriya.continuum.paper.modules.gamemode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ordering and value tests for the gamemode restore path, driven through a
 * recording {@link GameModeOps} — the production restore executes exactly the
 * same code, only the entity calls are scripted here.
 */
class GameModeApplyOrderTest {

    private static final class RecordingOps implements GameModeOps {
        final List<String> calls = new ArrayList<>();
        boolean allowFlightAfterSet = true;

        @Override
        public void setGameMode(String gameModeName) {
            calls.add("setGameMode=" + gameModeName);
        }

        @Override
        public void setAllowFlight(boolean allow) {
            calls.add("setAllowFlight=" + allow);
            allowFlightAfterSet = allow;
        }

        @Override
        public boolean allowFlight() {
            calls.add("allowFlight=" + allowFlightAfterSet);
            return allowFlightAfterSet;
        }

        @Override
        public void setFlying(boolean flying) {
            calls.add("setFlying=" + flying);
        }

        @Override
        public void setFlySpeed(float speed) {
            calls.add("setFlySpeed=" + speed);
        }

        @Override
        public void setWalkSpeed(float speed) {
            calls.add("setWalkSpeed=" + speed);
        }

        @Override
        public void setGliding(boolean gliding) {
            calls.add("setGliding=" + gliding);
        }

        @Override
        public void setSneaking(boolean sneaking) {
            calls.add("setSneaking=" + sneaking);
        }

        @Override
        public void setSprinting(boolean sprinting) {
            calls.add("setSprinting=" + sprinting);
        }
    }

    private static GameModePayload payload() {
        return new GameModePayload(GameModePayload.FORMAT, "CREATIVE",
                true, true, 0.35f, 0.45f, true, true, true);
    }

    @Test
    void restoreAppliesInDocumentedOrder() {
        RecordingOps ops = new RecordingOps();
        GameModeApply.apply(payload(), ops, GameModeRestoreConfig.ALL);

        List<String> order = ops.calls.stream().map(GameModeApplyOrderTest::methodName).toList();
        assertEquals(List.of(
                        "setGameMode",
                        "setAllowFlight",
                        "allowFlight",
                        "setFlying",
                        "setFlySpeed",
                        "setWalkSpeed",
                        "setGliding",
                        "setSneaking",
                        "setSprinting"),
                order);
    }

    @Test
    void flyingIsSkippedWhenServerForbidsFlight() {
        RecordingOps ops = new RecordingOps();
        // snapshot says flying=true, but the allow-flight write must gate it
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                false, true, 0.35f, 0.45f, true, true, true);
        GameModeApply.apply(payload, ops, GameModeRestoreConfig.ALL);

        assertTrue(ops.calls.contains("setAllowFlight=false"));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setFlying")),
                "a flight-forbidding server must never see setFlying: " + ops.calls);
        // fly speed still applies — it is inert without allow-flight
        assertTrue(ops.calls.contains("setFlySpeed=0.35"), ops.calls.toString());
    }

    @Test
    void flyingIsRestoredWhenFlightIsAllowed() {
        RecordingOps ops = new RecordingOps();
        GameModeApply.apply(payload(), ops, GameModeRestoreConfig.ALL);

        assertTrue(ops.calls.contains("setFlying=true"), ops.calls.toString());
    }

    @Test
    void restoreFlightFalseSkipsTheFlightTrioOnly() {
        RecordingOps ops = new RecordingOps();
        GameModeRestoreConfig config = new GameModeRestoreConfig(true, false, true);
        GameModeApply.apply(payload(), ops, config);

        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setAllowFlight")));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("allowFlight")));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setFlying")));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setFlySpeed")));
        assertTrue(ops.calls.contains("setGameMode=CREATIVE"));
        assertTrue(ops.calls.contains("setWalkSpeed=0.45"));
        assertTrue(ops.calls.contains("setGliding=true"));
    }

    @Test
    void restoreGameModeFalseSkipsOnlyTheMode() {
        RecordingOps ops = new RecordingOps();
        GameModeRestoreConfig config = new GameModeRestoreConfig(false, true, true);
        GameModeApply.apply(payload(), ops, config);

        assertFalse(ops.calls.stream().anyMatch(call -> call.startsWith("setGameMode")));
        assertTrue(ops.calls.contains("setAllowFlight=true"));
        assertTrue(ops.calls.contains("setFlying=true"));
    }

    @Test
    void restoreMovementFlagsFalseSkipsOnlyFlags() {
        RecordingOps ops = new RecordingOps();
        GameModeRestoreConfig config = new GameModeRestoreConfig(true, true, false);
        GameModeApply.apply(payload(), ops, config);

        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setGliding")));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setSneaking")));
        assertTrue(ops.calls.stream().noneMatch(call -> call.startsWith("setSprinting")));
        assertTrue(ops.calls.contains("setFlying=true"));
    }

    @Test
    void outOfRangeSpeedsAreClampedIntoApiRange() {
        RecordingOps ops = new RecordingOps();
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                true, false, 3.7f, -2.5f, false, false, false);
        GameModeApply.apply(payload, ops, GameModeRestoreConfig.ALL);

        assertTrue(ops.calls.contains("setFlySpeed=1.0"), ops.calls.toString());
        assertTrue(ops.calls.contains("setWalkSpeed=-1.0"), ops.calls.toString());
    }

    @Test
    void negativeSpeedsAreRestoredAsIs() {
        RecordingOps ops = new RecordingOps();
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                true, false, -0.5f, -0.25f, false, false, false);
        GameModeApply.apply(payload, ops, GameModeRestoreConfig.ALL);

        assertTrue(ops.calls.contains("setFlySpeed=-0.5"), ops.calls.toString());
        assertTrue(ops.calls.contains("setWalkSpeed=-0.25"), ops.calls.toString());
    }

    @Test
    void applyIsIdempotent() {
        RecordingOps ops = new RecordingOps();
        GameModeApply.apply(payload(), ops, GameModeRestoreConfig.ALL);
        List<String> first = List.copyOf(ops.calls);
        ops.calls.clear();
        GameModeApply.apply(payload(), ops, GameModeRestoreConfig.ALL);

        assertEquals(first, ops.calls);
    }

    private static String methodName(String call) {
        int eq = call.indexOf('=');
        return eq < 0 ? call : call.substring(0, eq);
    }
}
