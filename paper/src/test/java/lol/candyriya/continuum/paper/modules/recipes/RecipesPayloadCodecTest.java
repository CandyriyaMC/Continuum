package lol.candyriya.continuum.paper.modules.recipes;

import lol.candyriya.continuum.paper.modules.vitals.JacksonCodecBridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the recipes payload, driven through the same mapper
 * the production snapshot pipeline builds (see {@link JacksonCodecBridge}).
 */
class RecipesPayloadCodecTest {

    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    @Test
    void roundTripPreservesFormatAndRecipes() {
        RecipesPayload original = new RecipesPayload(RecipesPayload.FORMAT,
                List.of("minecraft:apple", "minecraft:crafting_table", "my_mod:copper_gear"));
        RecipesPayload decoded = codec.roundTrip(original, RecipesPayload.class);

        assertEquals(original.format(), decoded.format());
        assertEquals(original.recipes(), decoded.recipes());
    }

    @Test
    void wireFormatIsStableAndSnakeCase() {
        RecipesPayload payload = new RecipesPayload(RecipesPayload.FORMAT,
                List.of("minecraft:crafting_table", "my_mod:copper_gear"));
        String json = codec.toJson(payload);

        // exact shape: format first, recipes second, lowercase keys, no pretty printing
        assertEquals("{\"format\":1,\"recipes\":[\"minecraft:crafting_table\",\"my_mod:copper_gear\"]}", json);
    }

    @Test
    void emptyBookRoundTrips() {
        RecipesPayload payload = new RecipesPayload(RecipesPayload.FORMAT, List.of());
        String json = codec.toJson(payload);

        assertEquals("{\"format\":1,\"recipes\":[]}", json);
        assertEquals(payload, codec.roundTrip(payload, RecipesPayload.class));
    }

    @Test
    void unknownNamespacesSurviveTheRoundTripVerbatim() {
        RecipesPayload payload = new RecipesPayload(RecipesPayload.FORMAT,
                List.of("technic:copper_gear", "create:andesite_casing", "some_pack-2:deep/thing.v3"));
        RecipesPayload decoded = codec.roundTrip(payload, RecipesPayload.class);

        assertEquals("technic:copper_gear", decoded.recipes().get(0));
        assertEquals("create:andesite_casing", decoded.recipes().get(1));
        assertEquals("some_pack-2:deep/thing.v3", decoded.recipes().get(2));
    }

    @Test
    void unknownJsonPropertiesAreTolerated() {
        // forward compatibility: a newer build may add fields; older builds must still read the payload
        String json = "{\"format\":1,\"recipes\":[\"minecraft:apple\"],\"future_field\":true}";
        RecipesPayload decoded = codec.fromJson(json, RecipesPayload.class);

        assertEquals(RecipesPayload.FORMAT, decoded.format());
        assertEquals(List.of("minecraft:apple"), decoded.recipes());
    }

    @Test
    void futureFormatDecodesButFailsValidation() {
        String json = "{\"format\":99,\"recipes\":[\"minecraft:apple\"]}";
        RecipesPayload decoded = codec.fromJson(json, RecipesPayload.class);

        // decode succeeds (the mapper is tolerant), the validation gate is what rejects it
        assertEquals(99, decoded.format());
        assertTrue(!RecipesValidation.validate(decoded).valid());
    }

    @Test
    void duplicateKeysInJsonDecodeButFailValidation() {
        String json = "{\"format\":1,\"recipes\":[\"minecraft:apple\",\"minecraft:apple\"]}";
        RecipesPayload decoded = codec.fromJson(json, RecipesPayload.class);

        assertEquals(2, decoded.recipes().size());
        assertTrue(!RecipesValidation.validate(decoded).valid());
    }

    @Test
    void garbageRecipesValueFailsToDecode() {
        assertThrows(IllegalStateException.class,
                () -> codec.fromJson("{\"format\":1,\"recipes\":\"not a list\"}", RecipesPayload.class));
    }
}
