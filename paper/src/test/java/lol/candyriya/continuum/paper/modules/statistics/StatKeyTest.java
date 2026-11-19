package lol.candyriya.continuum.paper.modules.statistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatKeyTest {

    @Test
    void encodesUntypedKey() {
        assertEquals("untyped:deaths", StatKey.encodeUntyped("deaths"));
        assertEquals("untyped:play_one_minute", StatKey.encodeUntyped("play_one_minute"));
    }

    @Test
    void encodesQualifiedKeys() {
        assertEquals("item:use_item:diamond_sword",
                StatKey.encodeQualified(StatKey.Family.ITEM, "use_item", "diamond_sword"));
        assertEquals("block:mine_block:stone",
                StatKey.encodeQualified(StatKey.Family.BLOCK, "mine_block", "stone"));
        assertEquals("entity:kill_entity:zombie",
                StatKey.encodeQualified(StatKey.Family.ENTITY, "kill_entity", "zombie"));
    }

    @Test
    void parseRoundTripsEveryFamily() {
        for (String key : List.of(
                "untyped:deaths",
                "untyped:play_one_minute",
                "item:use_item:diamond_sword",
                "block:mine_block:stone",
                "entity:kill_entity:zombie",
                "entity:entity_killed_by:skeleton")) {
            Optional<StatKey.Decoded> decoded = StatKey.parse(key);
            assertTrue(decoded.isPresent(), key);
            assertEquals(key, decoded.get().encode());
        }
    }

    @Test
    void decodedFieldsAreExposed() {
        StatKey.Decoded decoded = StatKey.parse("entity:kill_entity:zombie").orElseThrow();
        assertEquals(StatKey.Family.ENTITY, decoded.family());
        assertEquals("kill_entity", decoded.stat());
        assertEquals("zombie", decoded.qualifier());
    }

    @Test
    void untypedDecodedHasNoQualifier() {
        StatKey.Decoded decoded = StatKey.parse("untyped:deaths").orElseThrow();
        assertEquals(StatKey.Family.UNTYPED, decoded.family());
        assertEquals("deaths", decoded.stat());
        assertEquals(null, decoded.qualifier());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "DEATHS",
            "untyped",
            "untyped:",
            "untyped:Deaths",
            "untyped:deaths:stone",
            "untyped:deaths:extra:parts",
            "item:use_item",
            "item:use_item:diamond_sword:extra",
            "Item:use_item:diamond_sword",
            "item:USE_ITEM:diamond_sword",
            "item:use_item:Diamond_Sword",
            "item::diamond_sword",
            "item:use_item:",
            "item:use_item:diamond-sword",
            "item:use_item:diamond sword",
            "block:mine_block:minecraft:stone",
            "entity:kill_entity:zombie:skeleton",
            "modded:use_item:diamond_sword",
            "item:use_item:",
            "nope:deaths"
    })
    void malformedKeysAreRejected(String key) {
        assertTrue(StatKey.parse(key).isEmpty(), "expected rejection: " + key);
    }

    @Test
    void unknownButWellFormedStatNamesParse() {
        // a snapshot from a newer build may carry stat constants this one does not
        // know yet — syntax is all parse() vouches for; resolution is a restore-time concern
        Optional<StatKey.Decoded> decoded = StatKey.parse("untyped:some_future_stat_2030");
        assertTrue(decoded.isPresent());
        assertEquals("some_future_stat_2030", decoded.get().stat());

        Optional<StatKey.Decoded> item = StatKey.parse("item:some_future_item_stat:some_future_material_2030");
        assertTrue(item.isPresent());
        assertEquals("some_future_material_2030", item.get().qualifier());

        Optional<StatKey.Decoded> entity = StatKey.parse("entity:some_future_entity_stat:some_future_mob_2030");
        assertTrue(entity.isPresent());
    }

    @Test
    void encodeUntypedRejectsJunkStat() {
        assertThrows(IllegalArgumentException.class, () -> StatKey.encodeUntyped("DEATHS"));
        assertThrows(IllegalArgumentException.class, () -> StatKey.encodeUntyped(""));
        assertThrows(IllegalArgumentException.class, () -> StatKey.encodeUntyped("play one minute"));
        assertThrows(IllegalArgumentException.class, () -> StatKey.encodeUntyped(null));
    }

    @Test
    void encodeQualifiedRejectsUntypedFamilyAndJunkQualifier() {
        assertThrows(IllegalArgumentException.class,
                () -> StatKey.encodeQualified(StatKey.Family.UNTYPED, "deaths", "stone"));
        assertThrows(IllegalArgumentException.class,
                () -> StatKey.encodeQualified(null, "use_item", "diamond_sword"));
        assertThrows(IllegalArgumentException.class,
                () -> StatKey.encodeQualified(StatKey.Family.ITEM, "use_item", "minecraft:diamond_sword"));
        assertThrows(IllegalArgumentException.class,
                () -> StatKey.encodeQualified(StatKey.Family.ITEM, "use_item", "Diamond_Sword"));
        assertThrows(IllegalArgumentException.class,
                () -> StatKey.encodeQualified(StatKey.Family.ITEM, "use_item", null));
    }

    @Test
    void isValidQualifierMatchesTheKeyCharset() {
        assertTrue(StatKey.isValidQualifier("diamond_sword"));
        assertTrue(StatKey.isValidQualifier("stone"));
        assertTrue(StatKey.isValidQualifier("a1_x"));
        assertTrue(StatKey.isValidQualifier("") == false);
        assertTrue(!StatKey.isValidQualifier("minecraft:stone"));
        assertTrue(!StatKey.isValidQualifier("Stone"));
        assertTrue(!StatKey.isValidQualifier(null));
    }

    @Test
    void distinctKeysNeverCollide() {
        // guard against accidental format drift producing duplicate wire spellings
        String a = StatKey.encodeQualified(StatKey.Family.ITEM, "use_item", "sugar");
        String b = StatKey.encodeQualified(StatKey.Family.BLOCK, "mine_block", "sugar");
        String c = StatKey.encodeUntyped("sugar");
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(b, c);
    }
}
