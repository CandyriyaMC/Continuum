package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotSummaryTest {

    private static SnapshotSummary summary(long id, String reason, String backend, List<String> modules) {
        return new SnapshotSummary(id, UUID.randomUUID(), 1, 41L, Instant.now(), reason, backend, modules);
    }

    @Test
    void validSummaryRoundTrips() {
        SnapshotSummary summary = summary(7, "AUTOSAVE", "survival-1", List.of("vitals", "inventory"));
        assertEquals(7, summary.snapshotId());
        assertEquals("AUTOSAVE", summary.reason());
        assertEquals(List.of("vitals", "inventory"), summary.modules());
    }

    @Test
    void snapshotIdMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> summary(0, "ok", "b", List.of()));
    }

    @Test
    void schemaVersionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotSummary(
                1, UUID.randomUUID(), 0, 0, Instant.now(), "ok", "b", List.of()));
    }

    @Test
    void fencingTokenMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotSummary(
                1, UUID.randomUUID(), 1, -1, Instant.now(), "ok", "b", List.of()));
    }

    @Test
    void reasonMustBeNonBlankAndAtMost32() {
        assertThrows(IllegalArgumentException.class, () -> summary(1, " ", "b", List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(1, "r".repeat(33), "b", List.of()));
        summary(1, "r".repeat(32), "b", List.of()); // boundary is fine
    }

    @Test
    void backendMustBeNonBlankAndAtMost64() {
        assertThrows(IllegalArgumentException.class, () -> summary(1, "ok", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> summary(1, "ok", "b".repeat(65), List.of()));
    }

    @Test
    void modulesAreCopiedAndUnmodifiable() {
        List<String> mutable = new java.util.ArrayList<>(List.of("vitals"));
        SnapshotSummary summary = summary(1, "ok", "b", mutable);
        mutable.add("injected");
        assertEquals(List.of("vitals"), summary.modules());
        assertThrows(UnsupportedOperationException.class, () -> summary.modules().add("x"));
    }

    @Test
    void nullModulesBecomeEmpty() {
        SnapshotSummary summary = summary(1, "ok", "b", null);
        assertTrue(summary.modules().isEmpty());
    }
}
