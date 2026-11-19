package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec-glue tests: normalization, budget enforcement, error context. The
 * byte-level component JSON itself is the platform-provided gson serializer,
 * exercised in production — here an in-memory double stands in (see
 * {@link InMemoryComponentJson}).
 */
class MetadataComponentCodecTest {

    private final MetadataComponentCodec codec = new MetadataComponentCodec(new InMemoryComponentJson());

    // ---- roundtrip wiring ----------------------------------------------

    @Test
    void decodeOfEncodedComponentReturnsTheSameComponent() {
        Component in = Component.text("[VIP] Stev").color(NamedTextColor.GOLD);
        assertEquals(in, codec.decode(codec.encode(in), "display_name"));
    }

    @Test
    void nestedAndDecoratedComponentsRoundTripThroughTheCodec() {
        Component in = Component.text("outer ").append(
                Component.text("inner").decorate(TextDecoration.BOLD).color(NamedTextColor.RED));
        assertEquals(in, codec.decode(codec.encode(in), "player_list_name"));
    }

    // ---- budget ---------------------------------------------------------

    @Test
    void oversizeComponentFailsEncodeLoudly() {
        Component huge = Component.text("a".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> codec.encodeLimited(huge, "display_name"));
        assertTrue(e.getMessage().contains("display_name"), e.getMessage());
        assertTrue(e.getMessage().contains("refusing to capture"), e.getMessage());
    }

    @Test
    void componentJustUnderTheBudgetPasses() {
        Component sized = Component.text("b".repeat(MetadataPayload.MAX_COMPONENT_CHARS - 40));
        String json = codec.encodeLimited(sized, "display_name");
        assertNotNull(json);
        assertTrue(json.length() <= MetadataPayload.MAX_COMPONENT_CHARS);
    }

    @Test
    void decodeOfAnOverBudgetStringIsRejectedBeforeParsing() {
        String oversized = "x".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> codec.decode(oversized, "player_list_name"));
        assertTrue(e.getMessage().contains("player_list_name"), e.getMessage());
        assertTrue(e.getMessage().contains("budget"), e.getMessage());
    }

    // ---- decode failures ------------------------------------------------

    @Test
    void blankInputFailsToDecode() {
        assertThrows(IllegalStateException.class, () -> codec.decode("", "display_name"));
    }

    @Test
    void unknownInputFailsToDecodeWithTheWireKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> codec.decode("definitely not a captured component", "display_name"));
        assertTrue(e.getMessage().contains("display_name"), e.getMessage());
    }

    // ---- normalization ---------------------------------------------------

    @Test
    void nullCapturedComponentNormalizesToAbsent() {
        assertNull(codec.normalizeForCapture(null, "Stev", "display_name"));
    }

    @Test
    void plainNameCapturedComponentNormalizesToAbsent() {
        // a never-customized player reports Component.text(name) — that is "absent", not a custom name
        assertNull(codec.normalizeForCapture(Component.text("Stev"), "Stev", "display_name"));
    }

    @Test
    void customCapturedComponentNormalizesToStoredJson() {
        String json = codec.normalizeForCapture(
                Component.text("[VIP] Stev").color(NamedTextColor.GOLD), "Stev", "display_name");
        assertNotNull(json);
        assertEquals(Component.text("[VIP] Stev").color(NamedTextColor.GOLD),
                codec.decode(json, "display_name"));
    }

    @Test
    void customListNameNormalizesWithItsOwnWireKey() {
        String json = codec.normalizeForCapture(
                Component.text("Stev").decorate(TextDecoration.BOLD), "Stev", "player_list_name");
        assertNotNull(json);
        assertEquals(Component.text("Stev").decorate(TextDecoration.BOLD),
                codec.decode(json, "player_list_name"));
    }
}
