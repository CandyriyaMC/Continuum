package lol.candyriya.continuum.paper.modules.location;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the payload's wire contract. Continuum maps record components to snake_case
 * JSON keys (see the common mapper), so the component names below ARE the JSON key
 * names — renaming one breaks every stored snapshot under format 1, and this test
 * exists to make that impossible to do silently.
 *
 * <p>Fully qualified mapping under the SNAKE_CASE convention:
 * {@code capturedAtEpochMilli} → {@code captured_at_epoch_milli},
 * {@code respawnPoint} → {@code respawn_point},
 * {@code lastDeathPoint} → {@code last_death_point}.</p>
 */
class LocationPayloadContractTest {

    private static List<String> componentNames(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getName).toList();
    }

    private static List<Class<?>> componentTypes(Class<? extends Record> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getType).toList();
    }

    @Test
    void payloadComponentNamesAreTheWireContract() {
        assertEquals(List.of("format", "capturedAtEpochMilli", "position", "respawnPoint", "lastDeathPoint"),
                componentNames(LocationPayload.class));
    }

    @Test
    void payloadComponentTypesAreTheWireContract() {
        assertEquals(List.of(int.class, long.class, PositionSnapshot.class, PositionSnapshot.class, PositionSnapshot.class),
                componentTypes(LocationPayload.class));
    }

    @Test
    void positionSnapshotComponentNamesAreTheWireContract() {
        assertEquals(List.of("world", "environment", "x", "y", "z", "yaw", "pitch"),
                componentNames(PositionSnapshot.class));
    }

    @Test
    void positionSnapshotComponentTypesAreTheWireContract() {
        assertEquals(List.of(String.class, String.class, double.class, double.class, double.class, float.class, float.class),
                componentTypes(PositionSnapshot.class));
    }

    @Test
    void formatConstantsAreStable() {
        assertEquals(1, LocationPayload.FORMAT);
        assertEquals(30_000_000.0, LocationValidation.MAX_COORDINATE);
    }

    @Test
    void moduleIdMatchesTheBuiltInLocationModule() {
        assertEquals("continuum.location", BuiltinModules.LOCATION);
        assertEquals(1, LocationPayload.FORMAT);
    }

    @Test
    void tolerantConstructorDoesNotThrowOnMissingSections() {
        // decode tolerance: a corrupt snapshot decodes to nulls here and is rejected by LocationValidation,
        // not by an exception inside the codec
        assertDoesNotThrow(() -> new LocationPayload(LocationPayload.FORMAT, 0L, null, null, null));
    }

    @Test
    void nullableSectionsAreRoundTripFriendly() {
        var full = new LocationPayload(LocationPayload.FORMAT, 42L,
                new PositionSnapshot("survival", "NORMAL", 1.5, 64.0, -2.5, 90.0f, 15.0f),
                new PositionSnapshot("survival", "NORMAL", 1.0, 65.0, -3.0, 0.0f, 0.0f),
                null);
        assertNull(full.lastDeathPoint());
        assertEquals("survival", full.position().world());

        var positionOnly = new LocationPayload(LocationPayload.FORMAT, 42L, full.position(), null, null);
        assertNull(positionOnly.respawnPoint());
        assertNull(positionOnly.lastDeathPoint());
    }

    @Test
    void payloadIsAnOrdinaryRecord() {
        // equals/hashCode come from the record itself — snapshots compare by value, which the history/rollback tooling relies on
        var a = new LocationPayload(LocationPayload.FORMAT, 1L,
                new PositionSnapshot("w", "NORMAL", 0, 64, 0, 0, 0), null, null);
        var b = new LocationPayload(LocationPayload.FORMAT, 1L,
                new PositionSnapshot("w", "NORMAL", 0, 64, 0, 0, 0), null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void nullPositionFailsValidationNotDecode() {
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L, null, null, null);
        var result = LocationValidation.check(payload);
        org.junit.jupiter.api.Assertions.assertFalse(result.valid());
        org.junit.jupiter.api.Assertions.assertTrue(result.reason().contains("position"));
    }

    @Test
    void unknownWorldNamesAreNotRejectedByValidation() {
        // an unknown world is a deployment reality, not a corrupt payload — restore skips it, validation passes it
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L,
                new PositionSnapshot("some-other-node-world", "NORMAL", 0, 64, 0, 0, 0), null, null);
        assertDoesNotThrow(() -> LocationValidation.check(payload));
    }

    @Test
    void listsInPolicyAreDefensivelyCopied() {
        var entries = new ArrayList<String>();
        entries.add("survival");
        var policy = new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION, entries, FallbackMode.SERVER_SPAWN);
        entries.clear();
        assertEquals(List.of("survival"), policy.excludeWorlds());
        assertThrows(UnsupportedOperationException.class, () -> policy.excludeWorlds().add("other"));
    }
}
