package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import lol.candyriya.continuum.paper.modules.vitals.JacksonCodecBridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the metadata payload, driven through the same mapper
 * the production snapshot pipeline builds (see {@code JacksonCodecBridge}).
 * Component strings inside the payload are produced by the real gson
 * component serializer — the same class the platform ships.
 */
class MetadataPayloadCodecTest {

    private final InMemoryComponentJson json = new InMemoryComponentJson();
    private final MetadataComponentCodec components = new MetadataComponentCodec(json);
    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    @Test
    void roundTripPreservesBothCustomNames() {
        String display = components.encode(Component.text("[VIP] Stev").color(NamedTextColor.GOLD));
        String list = components.encode(Component.text("Stev").decorate(TextDecoration.BOLD));
        MetadataPayload original = new MetadataPayload(MetadataPayload.FORMAT, display, list);

        MetadataPayload decoded = codec.roundTrip(original, MetadataPayload.class);

        assertEquals(original.format(), decoded.format());
        assertEquals(original.displayName(), decoded.displayName());
        assertEquals(original.playerListName(), decoded.playerListName());
    }

    @Test
    void roundTripPreservesTheAbsentState() {
        MetadataPayload original = new MetadataPayload(MetadataPayload.FORMAT, null, null);

        MetadataPayload decoded = codec.roundTrip(original, MetadataPayload.class);

        assertEquals(original, decoded);
    }

    @Test
    void roundTripPreservesMixedState() {
        String display = components.encode(Component.text("[VIP] Stev").color(NamedTextColor.GOLD));
        MetadataPayload original = new MetadataPayload(MetadataPayload.FORMAT, display, null);

        MetadataPayload decoded = codec.roundTrip(original, MetadataPayload.class);

        assertEquals(display, decoded.displayName());
        assertEquals(null, decoded.playerListName());
    }

    @Test
    void wireFormatIsStableAndSnakeCase() {
        String display = components.encode(Component.text("hello"));
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, display, null);
        String json = codec.toJson(payload);

        // exact shape: format first, keys in record order, snake_case, component embedded as a string
        assertTrue(json.startsWith("{\"format\":1,\"display_name\":"), json);
        assertTrue(json.contains("\"player_list_name\":null"), json);
    }

    @Test
    void absentWireFormatIsExplicit() {
        MetadataPayload payload = new MetadataPayload(MetadataPayload.FORMAT, null, null);
        assertEquals("{\"format\":1,\"display_name\":null,\"player_list_name\":null}", codec.toJson(payload));
    }

    @Test
    void unknownJsonPropertiesAreTolerated() {
        // forward compatibility: a newer build may add fields; older builds must still read the payload
        String json = "{\"format\":1,\"display_name\":null,\"player_list_name\":null,\"future_field\":true}";
        MetadataPayload decoded = codec.fromJson(json, MetadataPayload.class);

        assertEquals(MetadataPayload.FORMAT, decoded.format());
    }

    @Test
    void futureFormatDecodesButFailsValidation() {
        String json = "{\"format\":99,\"display_name\":null,\"player_list_name\":null}";
        MetadataPayload decoded = codec.fromJson(json, MetadataPayload.class);

        assertEquals(99, decoded.format());
        assertTrue(!MetadataPayloadValidation.validate(decoded, components).valid());
    }

    @Test
    void overBudgetComponentStringDecodesButFailsValidation() {
        String oversized = "x".repeat(MetadataPayload.MAX_COMPONENT_CHARS + 1);
        String json = "{\"format\":1,\"display_name\":\"" + oversized + "\",\"player_list_name\":null}";
        MetadataPayload decoded = codec.fromJson(json, MetadataPayload.class);

        assertTrue(decoded.displayName().length() > MetadataPayload.MAX_COMPONENT_CHARS);
        assertTrue(!MetadataPayloadValidation.validate(decoded, components).valid());
    }

    @Test
    void blankComponentStringDecodesButFailsValidation() {
        String json = "{\"format\":1,\"display_name\":\"   \",\"player_list_name\":null}";
        MetadataPayload decoded = codec.fromJson(json, MetadataPayload.class);

        assertTrue(!MetadataPayloadValidation.validate(decoded, components).valid());
    }

    @Test
    void malformedComponentJsonStringDecodesButFailsValidation() {
        // "hello" is not a component the gson serializer will accept
        String json = "{\"format\":1,\"display_name\":\"hello world not json\",\"player_list_name\":null}";
        MetadataPayload decoded = codec.fromJson(json, MetadataPayload.class);

        var result = MetadataPayloadValidation.validate(decoded, components);
        assertTrue(!result.valid());
        assertTrue(result.reason().contains("display_name"), result.reason());
    }

    @Test
    void numericComponentValueDecodesAsStringButFailsValidation() {
        // the tolerant mapper coerces a scalar into the String component — the validation gate is what rejects it
        MetadataPayload decoded = codec.fromJson("{\"format\":1,\"display_name\":123,\"player_list_name\":null}", MetadataPayload.class);

        assertEquals("123", decoded.displayName());
        assertTrue(!MetadataPayloadValidation.validate(decoded, components).valid());
    }

    @Test
    void unknownComponentKeysSurviveTheRoundTripVerbatim() {
        // components are opaque strings to the mapper — whatever the gson serializer wrote comes back identically
        String display = "{\"text\":\"weird\",\"color\":\"#1b1b1b\",\"extra\":[{\"text\":\"nested\",\"bold\":true}]}";
        MetadataPayload original = new MetadataPayload(MetadataPayload.FORMAT, display, null);

        MetadataPayload decoded = codec.roundTrip(original, MetadataPayload.class);

        assertEquals(display, decoded.displayName());
    }
}
