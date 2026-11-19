package lol.candyriya.continuum.paper.modules.advancements;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the payload's wire contract. Continuum maps record components to snake_case
 * JSON keys, so the component names below ARE the JSON key names — renaming one
 * breaks every stored snapshot under format 1, and this test exists to make that
 * impossible to do silently.
 */
class AdvancementsPayloadContractTest {

    private static List<String> componentNames(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getName).toList();
    }

    private static List<Class<?>> componentTypes(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getType).toList();
    }

    @Test
    void payloadComponentNamesAreTheWireContract() {
        assertEquals(List.of("format", "capturedAtEpochMilli", "advancements"),
                componentNames(AdvancementsPayload.class));
    }

    @Test
    void payloadComponentTypesAreTheWireContract() {
        assertEquals(List.of(int.class, long.class, List.class),
                componentTypes(AdvancementsPayload.class));
    }

    @Test
    void entryComponentNamesAreTheWireContract() {
        assertEquals(List.of("key", "awardedCriteria", "done", "awardedAt"),
                componentNames(AdvancementEntry.class));
    }

    @Test
    void entryComponentTypesAreTheWireContract() {
        assertEquals(List.of(String.class, List.class, boolean.class, Map.class),
                componentTypes(AdvancementEntry.class));
    }

    @Test
    void formatConstantsAreStable() {
        assertEquals(1, AdvancementsPayload.FORMAT);
        assertEquals(8_192, AdvancementsPayload.MAX_ADVANCEMENTS);
        assertEquals(256, AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT);
    }

    @Test
    void moduleIdMatchesTheBuiltInAdvancementsModule() {
        assertEquals("continuum.advancements", BuiltinModules.ADVANCEMENTS);
        assertEquals(1, new AdvancementsModule().version());
        assertEquals(BuiltinModules.ADVANCEMENTS, new AdvancementsModule().id());
    }

    @Test
    void advancementsListIsDefensivelyCopied() {
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("crafting_table"), false, Map.of());
        var entries = new ArrayList<>(List.of(entry));
        var payload = new AdvancementsPayload(AdvancementsPayload.FORMAT, 1L, entries);
        entries.clear();
        assertEquals(1, payload.advancements().size());
        assertThrows(UnsupportedOperationException.class, () -> payload.advancements().add(entry));
    }

    @Test
    void nullAdvancementsListNormalizesToEmpty() {
        var payload = new AdvancementsPayload(AdvancementsPayload.FORMAT, 1L, null);
        assertTrue(payload.advancements().isEmpty());
    }

    @Test
    void awardedAtIsDefensivelyCopiedAndSortedByCriterion() {
        var unsorted = new HashMap<String, Long>();
        unsorted.put("zebra", 3L);
        unsorted.put("alpha", 1L);
        unsorted.put("middle", 2L);
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("alpha", "middle", "zebra"), false, unsorted);
        unsorted.clear();
        assertEquals(List.of("alpha", "middle", "zebra"), List.copyOf(entry.awardedAt().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> entry.awardedAt().put("delta", 4L));
    }

    @Test
    void nullAwardedAtNormalizesToEmptyAndMapsAreStoredSorted() {
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("a", "b"), false, null);
        assertTrue(entry.awardedAt().isEmpty());

        var unsorted = new java.util.LinkedHashMap<String, Long>();
        unsorted.put("b", 2L);
        unsorted.put("a", 1L);
        var sorted = new AdvancementEntry("minecraft:story/mine_stone", List.of("a", "b"), false, unsorted);
        assertEquals(List.of("a", "b"), List.copyOf(sorted.awardedAt().keySet()));
    }

    @Test
    void nullKeysOrValuesInAwardedAtAreRejected() {
        var badKey = new HashMap<String, Long>();
        badKey.put(null, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancementEntry("minecraft:story/mine_stone", List.of("a"), false, badKey));
        var badValue = new HashMap<String, Long>();
        badValue.put("a", null);
        assertThrows(IllegalArgumentException.class,
                () -> new AdvancementEntry("minecraft:story/mine_stone", List.of("a"), false, badValue));
    }

    @Test
    void entryRejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> new AdvancementEntry(null, List.of(), false, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AdvancementEntry("   ", List.of(), false, Map.of()));
    }

    @Test
    void entryTrimsSurroundingWhitespaceInKey() {
        var entry = new AdvancementEntry("  minecraft:story/mine_stone  ", List.of(), false, Map.of());
        assertEquals("minecraft:story/mine_stone", entry.key());
    }
}
