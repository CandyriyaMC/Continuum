package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lol.candyriya.continuum.api.module.ValidationResult;

/**
 * The validation gate's reason strings are part of the contract — operators read
 * them from restore reports — so these tests pin down what gets rejected and
 * why, not just the boolean.
 */
class MetadataPayloadValidationTest {

    private final InMemoryComponentJson json = new InMemoryComponentJson();
    private final MetadataComponentCodec components = new MetadataComponentCodec(json);

    private String encode(Component component) {
        return components.encode(component);
    }

    private ValidationResult check(MetadataPayload payload) {
        // the gson component serializer is not on this test classpath — the in-memory double stands in
        return MetadataPayloadValidation.validate(payload, components);
    }

    @Test
    void fullyAbsentPayloadIsValid() {
        assertTrue(check(new MetadataPayload(MetadataPayload.FORMAT, null, null)).valid());
    }

    @Test
    void fullyCustomizedPayloadIsValid() {
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT,
                encode(Component.text("[VIP] Stev").color(NamedTextColor.GOLD)),
                encode(Component.text("Stev").color(NamedTextColor.YELLOW)));
        assertTrue(check(payload).valid());
    }

    @Test
    void nullPayloadIsRejected() {
        var result = check(null);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("null"), result.reason());
    }

    @Test
    void unsupportedFormatIsRejectedWithTheFormatInTheReason() {
        var result = check(new MetadataPayload(99, null, null));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("99"), result.reason());
        assertTrue(result.reason().contains(String.valueOf(MetadataPayload.FORMAT)), result.reason());
    }

    @Test
    void blankComponentJsonIsRejected() {
        var result = check(new MetadataPayload(MetadataPayload.FORMAT, "   ", null));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("display_name"), result.reason());
        assertTrue(result.reason().contains("blank"), result.reason());
    }

    @Test
    void overBudgetListNameIsRejectedWithTheBudgetInTheReason() {
        String oversized = "y".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1);
        var result = check(new MetadataPayload(MetadataPayload.FORMAT, null, oversized));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("player_list_name"), result.reason());
        assertTrue(result.reason().contains(String.valueOf(MetadataPayload.MAX_COMPONENT_CHARS)), result.reason());
    }

    @Test
    void malformedComponentJsonIsRejectedWithTheKeyInTheReason() {
        var result = check(
                new MetadataPayload(MetadataPayload.FORMAT, "not component json at all", null));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("display_name"), result.reason());
        assertTrue(result.reason().contains("not restorable"), result.reason());
    }

    @Test
    void malformedListNameIsRejectedWithTheKeyInTheReason() {
        var result = check(
                new MetadataPayload(MetadataPayload.FORMAT, null, "{\"text\":\"broken"));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("player_list_name"), result.reason());
    }

    @Test
    void validDisplayButBrokenListRejectsOnTheListKey() {
        var result = check(new MetadataPayload(MetadataPayload.FORMAT,
                encode(Component.text("Stev").color(NamedTextColor.GOLD)), "garbage"));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("player_list_name"), result.reason());
    }

    @Test
    void exactlyAtTheBudgetIsAccepted() {
        // a real gson component that lands close to the cap
        String display = encode(Component.text("a".repeat(MetadataPayload.MAX_COMPONENT_CHARS - 40)));
        assertTrue(display.length() <= MetadataPayload.MAX_COMPONENT_CHARS);
        assertTrue(check(new MetadataPayload(MetadataPayload.FORMAT, display, null)).valid());
    }

    @Test
    void validResultCarriesNoReason() {
        var result = check(new MetadataPayload(MetadataPayload.FORMAT, null, null));
        assertNull(result.reason());
    }
}
