package lol.candyriya.continuum.api.snapshot;

import lol.candyriya.continuum.api.module.InvalidModuleIdException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSnapshotTest {

    private static PlayerSnapshot sample() {
        return new PlayerSnapshot(
                42,
                1,
                UUID.randomUUID(),
                Instant.parse("2026-11-19T12:00:00Z"),
                "survival-1",
                7,
                Map.of("continuum.inventory", 1, "continuum.vitals", 2),
                SnapshotReason.AUTOSAVE,
                SyncResult.SUCCESS);
    }

    @Test
    void aWellFormedSnapshotValidates() {
        PlayerSnapshot snapshot = sample();

        assertEquals(42, snapshot.snapshotId());
        assertEquals(1, snapshot.moduleVersion("continuum.inventory").orElseThrow());
        assertEquals(2, snapshot.moduleVersion("continuum.vitals").orElseThrow());
        assertEquals(2, snapshot.moduleCount());
        assertTrue(snapshot.moduleVersion("continuum.recipes").isEmpty());
    }

    @Test
    void snapshotIdBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(0, 1, UUID.randomUUID(), Instant.now(), "s", 0, Map.of(),
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void schemaVersionBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(1, 0, UUID.randomUUID(), Instant.now(), "s", 0, Map.of(),
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void blankBackendIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "  ", 0, Map.of(),
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
        assertThrows(NullPointerException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), null, 0, Map.of(),
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void negativeFencingTokenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", -1, Map.of(),
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void moduleVersionsAreCopiedAndUnmodifiable() {
        Map<String, Integer> mutable = new LinkedHashMap<>();
        mutable.put("continuum.inventory", 1);
        PlayerSnapshot snapshot = new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", 0,
                mutable, SnapshotReason.AUTOSAVE, SyncResult.SUCCESS);

        mutable.put("continuum.vitals", 1);

        assertEquals(Map.of("continuum.inventory", 1), snapshot.moduleVersions());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.moduleVersions().put("continuum.recipes", 1));
    }

    @Test
    void moduleKeysMustBeWellFormed() {
        Map<String, Integer> versions = new HashMap<>();
        versions.put("Bad.Id", 1);

        assertThrows(InvalidModuleIdException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", 0, versions,
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void moduleVersionsMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", 0,
                        Map.of("continuum.inventory", 0), SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void nullModuleVersionIsRejected() {
        Map<String, Integer> versions = new HashMap<>();
        versions.put("continuum.inventory", null);

        assertThrows(NullPointerException.class,
                () -> new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", 0, versions,
                        SnapshotReason.AUTOSAVE, SyncResult.SUCCESS));
    }

    @Test
    void captureOrderIsPreserved() {
        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("continuum.attributes", 1);
        versions.put("continuum.vitals", 1);
        versions.put("continuum.inventory", 3);

        PlayerSnapshot snapshot = new PlayerSnapshot(1, 1, UUID.randomUUID(), Instant.now(), "s", 0,
                versions, SnapshotReason.TRANSFER, SyncResult.PARTIAL);

        assertEquals(
                java.util.List.of("continuum.attributes", "continuum.vitals", "continuum.inventory"),
                java.util.List.copyOf(snapshot.moduleVersions().keySet()));
    }
}
