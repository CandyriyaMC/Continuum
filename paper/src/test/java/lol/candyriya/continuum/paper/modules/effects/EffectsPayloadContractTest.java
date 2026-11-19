package lol.candyriya.continuum.paper.modules.effects;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the payload's wire contract. Continuum maps record components to snake_case
 * JSON keys, so the component names below ARE the JSON key names — renaming one
 * breaks every stored snapshot under format 1, and this test exists to make that
 * impossible to do silently.
 */
class EffectsPayloadContractTest {

    private static List<String> componentNames(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getName).toList();
    }

    private static List<Class<?>> componentTypes(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getType).toList();
    }

    @Test
    void payloadComponentNamesAreTheWireContract() {
        assertEquals(List.of("format", "capturedAtEpochMilli", "effects"),
                componentNames(EffectsPayload.class));
    }

    @Test
    void payloadComponentTypesAreTheWireContract() {
        assertEquals(List.of(int.class, long.class, List.class),
                componentTypes(EffectsPayload.class));
    }

    @Test
    void entryComponentNamesAreTheWireContract() {
        assertEquals(List.of("type", "durationTicks", "amplifier", "ambient", "particles", "icon", "instant"),
                componentNames(EffectEntry.class));
    }

    @Test
    void entryComponentTypesAreTheWireContract() {
        assertEquals(List.of(String.class, int.class, int.class, boolean.class, boolean.class, boolean.class, boolean.class),
                componentTypes(EffectEntry.class));
    }

    @Test
    void formatConstantsAreStable() {
        assertEquals(1, EffectsPayload.FORMAT);
        assertEquals(-1, EffectsPayload.INFINITE_DURATION_TICKS);
        assertEquals(50L, EffectsPayload.MILLIS_PER_TICK);
        assertEquals(20, EffectsPayload.TICKS_PER_SECOND);
        assertEquals(128, EffectsPayload.MAX_EFFECTS);
    }

    @Test
    void moduleIdMatchesTheBuiltInEffectsModule() {
        assertEquals("continuum.effects", BuiltinModules.EFFECTS);
    }

    @Test
    void effectsListIsDefensivelyCopied() {
        var entry = new EffectEntry("minecraft:speed", 100, 0, false, true, true, false);
        var entries = new ArrayList<EffectEntry>();
        entries.add(entry);
        var payload = new EffectsPayload(EffectsPayload.FORMAT, 1L, entries);
        entries.clear();
        assertEquals(1, payload.effects().size());
        // List.copyOf collections are immutable
        assertThrows(UnsupportedOperationException.class, () -> payload.effects().add(entry));
    }

    @Test
    void nullEffectsListNormalizesToEmpty() {
        var payload = new EffectsPayload(EffectsPayload.FORMAT, 1L, null);
        assertTrue(payload.effects().isEmpty());
    }

    @Test
    void entryRejectsBlankAndNullTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> new EffectEntry(null, 100, 0, false, true, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new EffectEntry("  ", 100, 0, false, true, true, false));
    }

    @Test
    void entryTrimsSurroundingWhitespaceInType() {
        var entry = new EffectEntry("  minecraft:speed  ", 100, 0, false, true, true, false);
        assertEquals("minecraft:speed", entry.type());
    }
}
