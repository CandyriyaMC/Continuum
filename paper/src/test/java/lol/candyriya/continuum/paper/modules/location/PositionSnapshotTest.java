package lol.candyriya.continuum.paper.modules.location;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionSnapshotTest {

    @Test
    void worldNameIsTrimmed() {
        var snapshot = new PositionSnapshot("  survival  ", "NORMAL", 1.0, 64.0, -2.0, 90.0f, 0.0f);
        assertEquals("survival", snapshot.world());
    }

    @Test
    void environmentIsTrimmedAndBlankBecomesNull() {
        var kept = new PositionSnapshot("survival", "  NETHER ", 0, 0, 0, 0, 0);
        assertEquals("NETHER", kept.environment());
        var dropped = new PositionSnapshot("survival", "   ", 0, 0, 0, 0, 0);
        assertNull(dropped.environment());
        var absent = new PositionSnapshot("survival", null, 0, 0, 0, 0, 0);
        assertNull(absent.environment());
    }

    @Test
    void blankWorldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot(null, "NORMAL", 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("   ", "NORMAL", 0, 0, 0, 0, 0));
    }

    @Test
    void nonFiniteCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("survival", "NORMAL", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("survival", "NORMAL", 0, Double.POSITIVE_INFINITY, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("survival", "NORMAL", 0, 0, Double.NEGATIVE_INFINITY, 0, 0));
    }

    @Test
    void nonFiniteYawPitchAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("survival", "NORMAL", 0, 0, 0, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new PositionSnapshot("survival", "NORMAL", 0, 0, 0, 0, Float.POSITIVE_INFINITY));
    }

    @Test
    void nullArgumentHandledByCallers() {
        // PositionSnapshot.of(Location) takes a live Location, so it cannot be unit-tested without a server;
        // the null/absent contract is covered at the module level (respawn/death sections are optional).
        // Here we only pin that the constructor-side record contract holds for optional sections.
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L,
                new PositionSnapshot("survival", "NORMAL", 0, 64, 0, 0, 0), null, null);
        assertNull(payload.respawnPoint());
        assertNull(payload.lastDeathPoint());
    }

    @Test
    void intLikeCoordinatesAreAccepted() {
        var snapshot = new PositionSnapshot("survival", "NORMAL", 100, 64, -900, 0, 0);
        assertEquals(100.0, snapshot.x());
        assertEquals(64.0, snapshot.y());
        assertEquals(-900.0, snapshot.z());
    }

    @Test
    void zeroIsAValidCoordinate() {
        var snapshot = new PositionSnapshot("survival", "NORMAL", 0, 0, 0, 0, 0);
        assertTrue(LocationValidation.withinBounds(snapshot));
    }
}
