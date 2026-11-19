package lol.candyriya.continuum.paper.modules.location;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationValidationTest {

    private static PositionSnapshot position(String world, double x, double y, double z) {
        return new PositionSnapshot(world, "NORMAL", x, y, z, 0.0f, 0.0f);
    }

    private static LocationPayload payload(int format, long capturedAt, PositionSnapshot position) {
        return new LocationPayload(format, capturedAt, position, null, null);
    }

    @Test
    void healthyPayloadPasses() {
        var result = LocationValidation.check(payload(LocationPayload.FORMAT, 1_763_456_789_000L, position("survival", 1.5, 64.0, -9.5)));
        assertTrue(result.valid(), result.reason());
    }

    @Test
    void unknownFormatIsRejected() {
        var result = LocationValidation.check(payload(2, 1L, position("survival", 0, 64, 0)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"), result.reason());
    }

    @Test
    void negativeCaptureTimeIsRejected() {
        var result = LocationValidation.check(payload(LocationPayload.FORMAT, -1L, position("survival", 0, 64, 0)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("captured_at_epoch_milli"), result.reason());
    }

    @Test
    void missingPositionSectionIsRejected() {
        var result = LocationValidation.check(payload(LocationPayload.FORMAT, 1L, null));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("position"), result.reason());
    }

    @Test
    void absurdCoordinatesAreRejected() {
        var atLimit = LocationValidation.check(payload(LocationPayload.FORMAT, 1L,
                position("survival", 30_000_000.0, -30_000_000.0, 30_000_000.0)));
        assertTrue(atLimit.valid(), "exactly at the limit is fine");

        var beyondLimit = LocationValidation.check(payload(LocationPayload.FORMAT, 1L,
                position("survival", 30_000_001.0, 0, 0)));
        assertFalse(beyondLimit.valid());

        var beyondLimitY = LocationValidation.check(payload(LocationPayload.FORMAT, 1L,
                position("survival", 0, -30_000_001.0, 0)));
        assertFalse(beyondLimitY.valid());

        var beyondLimitZ = LocationValidation.check(payload(LocationPayload.FORMAT, 1L,
                position("survival", 0, 0, 30_000_001.0)));
        assertFalse(beyondLimitZ.valid());
    }

    @Test
    void corruptRespawnPointIsRejected() {
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L,
                position("survival", 0, 64, 0),
                new PositionSnapshot("survival", "NORMAL", 0, 0, 30_000_001.0, 0, 0),
                null);
        var result = LocationValidation.check(payload);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("respawn point"), result.reason());
    }

    @Test
    void corruptLastDeathPointIsRejected() {
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L,
                position("survival", 0, 64, 0),
                null,
                new PositionSnapshot("nether", "NETHER", 30_000_001.0, 0, 0, 0, 0));
        var result = LocationValidation.check(payload);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("last death point"), result.reason());
    }

    @Test
    void unknownWorldNameInAnySectionIsAcceptedByValidation() {
        // unknown worlds are a restore-time concern (skipped with a note), never a validation failure
        var payload = new LocationPayload(LocationPayload.FORMAT, 1L,
                position("whatever-world", 0, 64, 0),
                new PositionSnapshot("respawn-elsewhere", "NORMAL", 1.0, 65.0, 1.0, 0, 0),
                new PositionSnapshot("death-anchor", "THE_END", 2.0, 65.0, 2.0, 0, 0));
        assertTrue(LocationValidation.check(payload).valid());
    }

    @Test
    void withinBoundsMatchesTheLimit() {
        assertTrue(LocationValidation.withinBounds(position("w", 30_000_000.0, 30_000_000.0, 30_000_000.0)));
        assertTrue(LocationValidation.withinBounds(position("w", -30_000_000.0, -30_000_000.0, -30_000_000.0)));
        assertFalse(LocationValidation.withinBounds(position("w", 30_000_000.1, 0, 0)));
        assertFalse(LocationValidation.withinBounds(null));
    }
}
