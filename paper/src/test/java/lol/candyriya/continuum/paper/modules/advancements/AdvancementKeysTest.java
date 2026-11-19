package lol.candyriya.continuum.paper.modules.advancements;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "minecraft:story/mine_stone",
            "minecraft:foo",
            "a:b",
            "continuum.test:path/with-dashes_under.dots",
            "my_mod:custom/advancement",
            "a0-_.:b0-_./c",
            "1:2",
    })
    void validKeysAreAccepted(String key) {
        assertTrue(AdvancementKeys.isValid(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "noColonAtAll",
            ":missing-namespace",
            "missing-path:",
            "a:b:c",
            "::",
            "Mincraft:story/mine_stone",
            "minecraft:Story/Mine_Stone",
            "minecraft:MINE_STONE",
            "minecraft:path with spaces",
            "minecraft:path\u0000with-control-char",
            "minecraft:path+plus",
            "minecraft:path\there",
            "minecraft:path\nhere",
            "namespace with space:path",
            "namespace:path/where space",
            "minecraft:path%20encoded",
    })
    void invalidKeysAreRejected(String key) {
        assertFalse(AdvancementKeys.isValid(key), key);
    }

    @Test
    void nullKeyIsRejected() {
        assertFalse(AdvancementKeys.isValid(null));
    }

    @Test
    void emptyKeyIsRejected() {
        assertFalse(AdvancementKeys.isValid(""));
    }

    @Test
    void exactlyMaxLengthIsAccepted() {
        // 1 (namespace) + 1 (colon) + MAX_KEY_LENGTH - 2 (path) = MAX_KEY_LENGTH
        String key = "a:" + "p".repeat(AdvancementKeys.MAX_KEY_LENGTH - 2);
        assertEquals(AdvancementKeys.MAX_KEY_LENGTH, key.length());
        assertTrue(AdvancementKeys.isValid(key));
    }

    @Test
    void oneOverMaxLengthIsRejected() {
        String key = "a:" + "p".repeat(AdvancementKeys.MAX_KEY_LENGTH - 1);
        assertEquals(AdvancementKeys.MAX_KEY_LENGTH + 1, key.length());
        assertFalse(AdvancementKeys.isValid(key));
    }

    @Test
    void pathMayEndInSlashButBreaksResourceLocationRulesOnlyThroughGrammar() {
        // a trailing slash is actually part of the allowed path grammar ([a-z0-9/._-]+),
        // this pins that the validator does not invent its own rules beyond the grammar
        assertTrue(AdvancementKeys.isValid("minecraft:story/"));
    }

    @Test
    void requireValidTrimsAndReturns() {
        assertEquals("minecraft:story/mine_stone",
                AdvancementKeys.requireValid("  minecraft:story/mine_stone  "));
    }

    @Test
    void requireValidThrowsOnInvalidKey() {
        assertThrows(IllegalArgumentException.class,
                () -> AdvancementKeys.requireValid("not a key"));
    }
}
