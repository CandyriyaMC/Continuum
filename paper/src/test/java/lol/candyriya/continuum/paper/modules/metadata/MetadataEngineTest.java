package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture/restore behavior of the metadata engine against an in-memory fake.
 * The fake records every platform write so the tests can pin down exactly what
 * a restore touches.
 */
class MetadataEngineTest {

    /** In-memory fake of the two profile surfaces the module is allowed to touch. */
    private static final class FakeProfileOps implements ProfileOps {
        String plainName = "Stev";
        Component displayName;
        Component listName;
        final List<String> writeLog = new ArrayList<>();

        @Override
        public String playerName() {
            return plainName;
        }

        @Override
        public @Nullable Component displayName() {
            return displayName;
        }

        @Override
        public @Nullable Component listName() {
            return listName;
        }

        @Override
        public void setDisplayName(@Nullable Component displayName) {
            writeLog.add(displayName == null ? "display_name:reset" : "display_name:set");
            this.displayName = displayName;
        }

        @Override
        public void setListName(@Nullable Component playerListName) {
            writeLog.add(playerListName == null ? "player_list_name:reset" : "player_list_name:set");
            this.listName = playerListName;
        }
    }

    private final InMemoryComponentJson json = new InMemoryComponentJson();
    private final MetadataComponentCodec codec = new MetadataComponentCodec(json);
    private final MetadataEngine engine = new MetadataEngine(codec);

    private String encode(Component component) {
        return codec.encode(component);
    }

    // ---- capture ---------------------------------------------------------

    @Test
    void captureOfANeverCustomizedPlayerProducesAbsentPayload() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.displayName = Component.text("Stev");
        ops.listName = Component.text("Stev");

        MetadataPayload payload = engine.capture(ops);

        assertEquals(MetadataPayload.FORMAT, payload.format());
        assertNull(payload.displayName());
        assertNull(payload.playerListName());
    }

    @Test
    void captureOfCustomDisplayNameRoundTripsToDecodedComponent() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.displayName = Component.text("[VIP] Stev").color(NamedTextColor.GOLD);

        MetadataPayload payload = engine.capture(ops);

        assertNotNull(payload.displayName());
        assertNull(payload.playerListName());
        assertEquals(Component.text("[VIP] Stev").color(NamedTextColor.GOLD),
                codec.decode(payload.displayName(), "display_name"));
    }

    @Test
    void captureOfCustomListNameRoundTripsToDecodedComponent() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.listName = Component.text("Stev").decorate(TextDecoration.BOLD);

        MetadataPayload payload = engine.capture(ops);

        assertNull(payload.displayName());
        assertNotNull(payload.playerListName());
        assertEquals(Component.text("Stev").decorate(TextDecoration.BOLD),
                codec.decode(payload.playerListName(), "player_list_name"));
    }

    @Test
    void captureWhereOnlyTheListNameIsCustomizedKeepsDisplayNameAbsent() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.displayName = Component.text("Stev"); // plain — normalized away
        ops.listName = Component.text("S").color(NamedTextColor.RED);

        MetadataPayload payload = engine.capture(ops);

        assertNull(payload.displayName());
        assertNotNull(payload.playerListName());
    }

    @Test
    void captureRejectsAnOversizeComponentLoudly() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.displayName = Component.text("a".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> engine.capture(ops));
        assertTrue(e.getMessage().contains("display_name"), e.getMessage());
        assertTrue(e.getMessage().contains("refusing to capture"), e.getMessage());
    }

    @Test
    void captureFailsWhenThePlatformGivesNoPlayerName() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.plainName = "  ";

        assertThrows(IllegalStateException.class, () -> engine.capture(ops));
    }

    // ---- restore ----------------------------------------------------------

    @Test
    void restoreAppliesBothCustomComponentsInWireOrder() {
        FakeProfileOps ops = new FakeProfileOps();
        Component display = Component.text("[VIP] Stev").color(NamedTextColor.GOLD);
        Component list = Component.text("Stev").decorate(TextDecoration.BOLD);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, encode(display), encode(list));

        RestoreReport report = engine.restore(ops, payload);

        assertEquals(List.of("display_name:set", "player_list_name:set"), ops.writeLog);
        assertEquals(display, ops.displayName);
        assertEquals(list, ops.listName);
        assertEquals(List.of("display_name", "player_list_name"), report.applied());
        assertEquals(List.of(), report.reset());
        assertEquals(List.of(), report.failed());
    }

    @Test
    void restoreOfAbsentPayloadResetsEverythingToTheVanillaDefault() {
        FakeProfileOps ops = new FakeProfileOps();
        ops.displayName = Component.text("[VIP] Stev"); // stale custom state on the target
        ops.listName = Component.text("Stev").decorate(TextDecoration.BOLD);

        RestoreReport report = engine.restore(ops, new MetadataPayload(MetadataPayload.FORMAT, null, null));

        assertEquals(List.of("display_name:reset", "player_list_name:reset"), ops.writeLog);
        assertNull(ops.displayName);
        assertNull(ops.listName);
        assertEquals(List.of(), report.applied());
        assertEquals(List.of("display_name", "player_list_name"), report.reset());
        assertEquals(List.of(), report.failed());
    }

    @Test
    void restoreOfMixedPayloadAppliesCustomAndResetsTheRest() {
        FakeProfileOps ops = new FakeProfileOps();
        Component display = Component.text("[VIP] Stev").color(NamedTextColor.GOLD);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, encode(display), null);

        RestoreReport report = engine.restore(ops, payload);

        assertEquals(List.of("display_name:set", "player_list_name:reset"), ops.writeLog);
        assertEquals(display, ops.displayName);
        assertNull(ops.listName);
        assertEquals(List.of("display_name"), report.applied());
        assertEquals(List.of("player_list_name"), report.reset());
        assertEquals(List.of(), report.failed());
    }

    @Test
    void restoreOfAMalformedDisplayNameSkipsThatKeyAndStillAppliesTheListName() {
        FakeProfileOps ops = new FakeProfileOps();
        Component list = Component.text("Stev").decorate(TextDecoration.BOLD);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, "{\"text\":\"unterminated", encode(list));

        RestoreReport report = engine.restore(ops, payload);

        // the display write never happened — the target's stale state stays untouched
        assertEquals(List.of("player_list_name:set"), ops.writeLog);
        assertEquals(list, ops.listName);
        assertEquals(List.of("player_list_name"), report.applied());
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).startsWith("display_name"), report.failed().toString());
        assertTrue(report.hadFailures());
    }

    @Test
    void restoreSkipsAStoredKeyThatExceedsTheBudget() {
        FakeProfileOps ops = new FakeProfileOps();
        String oversized = "x".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, oversized, null);

        RestoreReport report = engine.restore(ops, payload);

        assertEquals(List.of("player_list_name:reset"), ops.writeLog);
        assertEquals(List.of(), report.applied());
        assertEquals(List.of("player_list_name"), report.reset());
        assertEquals(1, report.failed().size());
        assertTrue(report.failed().get(0).contains("over budget"), report.failed().toString());
    }

    @Test
    void restoreIsIdempotentForTheSamePayload() {
        FakeProfileOps ops = new FakeProfileOps();
        Component display = Component.text("[VIP] Stev").color(NamedTextColor.GOLD);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, encode(display), null);

        engine.restore(ops, payload);
        engine.restore(ops, payload);

        assertEquals(List.of("display_name:set", "player_list_name:reset",
                        "display_name:set", "player_list_name:reset"),
                ops.writeLog);
        assertEquals(display, ops.displayName);
    }

    // ---- contract: excluded keys are never touched ------------------------

    @Test
    void engineWritesOnlyTheTwoSyncedKeys() {
        FakeProfileOps ops = new FakeProfileOps();
        Component display = Component.text("x").color(NamedTextColor.GOLD);
        Component list = Component.text("y").decorate(TextDecoration.BOLD);
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, encode(display), encode(list));

        engine.restore(ops, payload);

        for (String entry : ops.writeLog) {
            assertTrue(entry.startsWith("display_name:") || entry.startsWith("player_list_name:"),
                    "unexpected key written: " + entry);
        }
    }
}
