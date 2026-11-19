package lol.candyriya.continuum.paper.modules.attributes;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module's contract metadata (single source of truth for the SyncModule
 * implementation). Engine-level behavior needs a live server and is covered by
 * the pure layer tests plus in-game verification.
 */
class AttributesContractTest {

    @Test
    void idIsTheReservedAttributesId() {
        assertEquals(BuiltinModules.ATTRIBUTES, AttributesContract.ID);
        assertEquals("continuum.attributes", AttributesContract.ID);
    }

    @Test
    void schemaVersionIsOne() {
        assertEquals(1, AttributesContract.SCHEMA_VERSION);
        assertTrue(AttributesContract.SCHEMA_VERSION >= 1);
    }

    @Test
    void priorityLandsBelowTheDefaultSoAttributesRestoreBeforeVitals() {
        assertTrue(AttributesContract.PRIORITY < lol.candyriya.continuum.api.module.SyncModule.DEFAULT_PRIORITY,
                "attributes must restore before vitals: priority " + AttributesContract.PRIORITY);
        assertEquals(90, AttributesContract.PRIORITY);
    }

    @Test
    void dependenciesAreEmptyByDesign() {
        // attributes restore before vitals; depending on vitals here would cycle
        assertEquals(java.util.List.of(), AttributesContract.DEPENDENCIES);
    }

    @Test
    void aGarbageOnlyPayloadProducesNoUsableEntriesAndRawCount() {
        // the module refuses restore in exactly this situation (no silent defaults) —
        // assert the signal the module keys its refusal on
        var decoded = AttributesPayloadCodec.decode(java.util.Map.of(
                "format", 1,
                "attributes", java.util.List.of(
                        java.util.Map.of("key", "broken-key", "base", 1.0),
                        java.util.Map.of("key", "also-broken", "base", 2.0))));

        assertTrue(decoded.attributes().isEmpty());
        assertEquals(2, decoded.rawEntries());
    }
}
