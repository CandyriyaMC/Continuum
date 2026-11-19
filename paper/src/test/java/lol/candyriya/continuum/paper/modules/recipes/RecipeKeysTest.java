package lol.candyriya.continuum.paper.modules.recipes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeKeysTest {

    @Test
    void vanillaAndModdedKeysAreValid() {
        assertTrue(RecipeKeys.isValid("minecraft:crafting_table"));
        assertTrue(RecipeKeys.isValid("minecraft:foo/bar_baz.qux-1"));
        assertTrue(RecipeKeys.isValid("my_mod:copper_gear"));
        assertTrue(RecipeKeys.isValid("bukkit:debug_recipe"));
        assertTrue(RecipeKeys.isValid("continuum:internal_test"));
        assertTrue(RecipeKeys.isValid("a:b"));
        assertTrue(RecipeKeys.isValid("namespace.with.dots:path_with.everything-12/slash"));
    }

    @Test
    void nullAndEmptyKeysAreInvalid() {
        assertFalse(RecipeKeys.isValid(null));
        assertFalse(RecipeKeys.isValid(""));
    }

    @Test
    void missingOrMalformedColonIsInvalid() {
        // a bare word must NOT be defaulted to minecraft: — stored keys carry an explicit namespace
        assertFalse(RecipeKeys.isValid("crafting_table"));
        assertFalse(RecipeKeys.isValid(":crafting_table"));
        assertFalse(RecipeKeys.isValid("minecraft:"));
        assertFalse(RecipeKeys.isValid("minecraft:foo:bar"));
    }

    @Test
    void uppercaseAndIllegalCharactersAreInvalid() {
        assertFalse(RecipeKeys.isValid("Minecraft:stone"));
        assertFalse(RecipeKeys.isValid("minecraft:Stone"));
        assertFalse(RecipeKeys.isValid("my mod:stone"));
        assertFalse(RecipeKeys.isValid("minecraft:stone sword"));
        assertFalse(RecipeKeys.isValid("minecraft:stone$sword"));
        assertFalse(RecipeKeys.isValid("minecraft:стонь"));
        assertFalse(RecipeKeys.isValid("minecraft/stone:stone"));
        assertFalse(RecipeKeys.isValid("MINECRAFT:STONE"));
    }

    @Test
    void overlongKeyIsInvalid() {
        String huge = "minecraft:" + "x".repeat(Short.MAX_VALUE);
        assertFalse(RecipeKeys.isValid(huge));
    }

    @Test
    void normalizeCaptureSortsAndDeduplicates() {
        List<String> input = List.of(
                "minecraft:stone",
                "my_mod:copper_gear",
                "minecraft:apple",
                "minecraft:stone", // dupe
                "bukkit:debug_recipe");
        List<String> sorted = RecipeKeys.normalizeCapture(input);

        assertEquals(List.of("bukkit:debug_recipe", "minecraft:apple", "minecraft:stone", "my_mod:copper_gear"), sorted);
        assertThrows(UnsupportedOperationException.class, () -> sorted.add("minecraft:dirt"));
    }

    @Test
    void normalizeCaptureAcceptsEmpty() {
        assertEquals(List.of(), RecipeKeys.normalizeCapture(Set.of()));
    }

    @Test
    void normalizeCaptureThrowsOnInvalidKey() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> RecipeKeys.normalizeCapture(List.of("minecraft:ok", "NOT A KEY")));
        assertTrue(thrown.getMessage().contains("NOT A KEY"), thrown.getMessage());
    }

    @Test
    void normalizeCaptureThrowsPastTheCap() {
        List<String> oversized = new ArrayList<>(RecipesPayload.MAX_RECIPES + 1);
        for (int i = 0; i <= RecipesPayload.MAX_RECIPES; i++) {
            oversized.add(String.format("minecraft:recipe_%04d", i));
        }
        assertThrows(IllegalArgumentException.class, () -> RecipeKeys.normalizeCapture(oversized));
    }

    @Test
    void normalizeCaptureAtTheCapPasses() {
        Set<String> exactly = new HashSet<>();
        for (int i = 0; i < RecipesPayload.MAX_RECIPES; i++) {
            exactly.add(String.format("minecraft:recipe_%04d", i));
        }
        List<String> normalized = RecipeKeys.normalizeCapture(exactly);
        assertEquals(RecipesPayload.MAX_RECIPES, normalized.size());
        assertEquals(RecipesPayload.MAX_RECIPES, new HashSet<>(normalized).size());
        // sorted output contract
        Iterator<String> it = normalized.iterator();
        String prev = it.next();
        while (it.hasNext()) {
            String next = it.next();
            assertTrue(prev.compareTo(next) < 0, prev + " should sort before " + next);
            prev = next;
        }
    }

    @Test
    void normalizeLocalDropsNullsAndSorts() {
        Set<String> local = new HashSet<>();
        local.add("minecraft:stone");
        local.add(null);
        local.add("bukkit:debug_recipe");
        List<String> normalized = RecipeKeys.normalizeLocal(local);
        assertEquals(List.of("bukkit:debug_recipe", "minecraft:stone"), normalized);
    }

    @Test
    void normalizeLocalToleratesNullCollection() {
        assertEquals(List.of(), RecipeKeys.normalizeLocal(null));
    }

    @Test
    void firstDuplicateFindsOffender() {
        assertEquals("minecraft:stone",
                RecipeKeys.firstDuplicate(List.of("minecraft:apple", "minecraft:stone", "my_mod:x", "minecraft:stone")));
    }

    @Test
    void firstDuplicateReturnsNullWhenUnique() {
        assertNull(RecipeKeys.firstDuplicate(List.of("minecraft:apple", "minecraft:stone")));
        assertNull(RecipeKeys.firstDuplicate(List.of()));
    }

    @Test
    void firstInvalidIndexLocatesOffender() {
        assertEquals(2, RecipeKeys.firstInvalidIndex(List.of("minecraft:apple", "minecraft:stone", "garbage key")));
        assertEquals(-1, RecipeKeys.firstInvalidIndex(List.of("minecraft:apple", "minecraft:stone")));
        assertEquals(0, RecipeKeys.firstInvalidIndex(List.of("")));
    }
}
