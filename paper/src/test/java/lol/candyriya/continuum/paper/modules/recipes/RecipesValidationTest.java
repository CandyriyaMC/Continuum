package lol.candyriya.continuum.paper.modules.recipes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipesValidationTest {

    private static RecipesPayload valid() {
        return new RecipesPayload(RecipesPayload.FORMAT,
                List.of("minecraft:apple", "minecraft:crafting_table", "my_mod:copper_gear"));
    }

    @Test
    void validPayloadPasses() {
        assertTrue(RecipesValidation.validate(valid()).valid());
    }

    @Test
    void emptyBookIsValid() {
        assertTrue(RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT, List.of())).valid());
    }

    @Test
    void nullPayloadIsInvalid() {
        assertFalse(RecipesValidation.validate(null).valid());
    }

    @Test
    void unknownFormatIsInvalid() {
        assertFalse(RecipesValidation.validate(new RecipesPayload(2, List.of())).valid());
        assertFalse(RecipesValidation.validate(new RecipesPayload(0, List.of())).valid());
        assertFalse(RecipesValidation.validate(new RecipesPayload(-1, List.of())).valid());
    }

    @Test
    void nullRecipesListIsInvalid() {
        assertFalse(RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT, null)).valid());
    }

    @Test
    void payloadAtTheCapPasses() {
        List<String> exactly = new ArrayList<>(RecipesPayload.MAX_RECIPES);
        for (int i = 0; i < RecipesPayload.MAX_RECIPES; i++) {
            exactly.add(String.format("minecraft:recipe_%04d", i));
        }
        assertTrue(RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT, exactly)).valid());
    }

    @Test
    void payloadPastTheCapIsInvalid() {
        List<String> oversized = new ArrayList<>(RecipesPayload.MAX_RECIPES + 1);
        for (int i = 0; i <= RecipesPayload.MAX_RECIPES; i++) {
            oversized.add(String.format("minecraft:recipe_%04d", i));
        }
        var result = RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT, oversized));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("5000"), result.reason());
    }

    @Test
    void invalidKeyIsRejectedWithIndex() {
        var result = RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT,
                List.of("minecraft:apple", "not a key", "minecraft:stone")));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("not a key"), result.reason());
        assertTrue(result.reason().contains("index 1"), result.reason());
    }

    @Test
    void nullElementIsRejectedWithIndex() {
        var withNull = new java.util.ArrayList<String>();
        withNull.add("minecraft:apple");
        withNull.add(null);
        var result = RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT, withNull));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("index 1"), result.reason());
    }

    @Test
    void duplicateKeysAreRejectedWithName() {
        var result = RecipesValidation.validate(new RecipesPayload(RecipesPayload.FORMAT,
                List.of("minecraft:apple", "my_mod:copper_gear", "minecraft:apple")));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("minecraft:apple"), result.reason());
        assertTrue(result.reason().contains("duplicate"), result.reason());
    }

    @Test
    void unknownNamespacesAreNotAValidationFailure() {
        // datapacks and modded servers — restore decides known/unknown, validation must not
        var payload = new RecipesPayload(RecipesPayload.FORMAT,
                List.of("technic:copper_gear", "create:andesite_casing", "minecraft:stone"));
        assertTrue(RecipesValidation.validate(payload).valid());
    }

    @Test
    void malformedFormatReasonMentionsExpectedVersion() {
        assertEquals("unsupported payload format 7 (expected 1)",
                RecipesValidation.validate(new RecipesPayload(7, List.of())).reason());
    }
}
