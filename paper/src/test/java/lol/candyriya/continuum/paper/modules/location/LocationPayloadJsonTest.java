package lol.candyriya.continuum.paper.modules.location;

import lol.candyriya.continuum.common.internal.JacksonMappers;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips the payload through the shared wire mapper (snake_case keys, unknown
 * properties tolerated) and pins the exact JSON shape format 1 snapshots are stored
 * under. This is the contract the snapshot store relies on: renaming a record
 * component or an enum constant silently breaks every stored snapshot, so the keys
 * are asserted verbatim, byte for byte.
 */
class LocationPayloadJsonTest {

    private static final JsonMapper MAPPER = JacksonMappers.create();

    private static PositionSnapshot snapshot(String world, String environment, double x, double y, double z) {
        return new PositionSnapshot(world, environment, x, y, z, 90.0f, -15.0f);
    }

    private static LocationPayload fullPayload() {
        return new LocationPayload(LocationPayload.FORMAT, 1_763_456_789_000L,
                snapshot("survival", "NORMAL", 100.5, 64.0, -877.25),
                snapshot("survival", "NORMAL", 90.0, 65.0, -800.0),
                snapshot("nether", "NETHER", 10.0, 64.0, 20.0));
    }

    @Test
    void serializedPayloadUsesTheSnakeCaseWireKeys() {
        JsonNode tree = MAPPER.valueToTree(fullPayload());
        assertEquals(1, tree.get("format").asInt());
        assertEquals(1_763_456_789_000L, tree.get("captured_at_epoch_milli").asLong());
        JsonNode position = tree.get("position");
        assertEquals("survival", position.get("world").asString());
        assertEquals("NORMAL", position.get("environment").asString());
        assertTrue(position.get("x").isDouble());
        assertTrue(position.get("yaw").isNumber());
        assertEquals("survival", tree.get("respawn_point").get("world").asString());
        assertEquals("nether", tree.get("last_death_point").get("world").asString());
        assertNull(tree.get("capturedAtEpochMilli"), "camelCase keys are not part of the wire format");
    }

    @Test
    void roundTripPreservesThePayloadByValue() {
        LocationPayload original = fullPayload();
        LocationPayload decoded = MAPPER.treeToValue(MAPPER.valueToTree(original), LocationPayload.class);
        assertEquals(original, decoded);
        assertEquals(original.position(), decoded.position());
        assertEquals(original.respawnPoint(), decoded.respawnPoint());
        assertEquals(original.lastDeathPoint(), decoded.lastDeathPoint());
    }

    @Test
    void nullOptionalSectionsRoundTripAsJsonNull() {
        var payload = new LocationPayload(LocationPayload.FORMAT, 1_763_456_789_000L,
                snapshot("survival", "NORMAL", 1.0, 64.0, 1.0), null, null);
        JsonNode tree = MAPPER.valueToTree(payload);
        assertTrue(tree.get("respawn_point").isNull());
        assertTrue(tree.get("last_death_point").isNull());

        LocationPayload decoded = MAPPER.treeToValue(tree, LocationPayload.class);
        assertNull(decoded.respawnPoint());
        assertNull(decoded.lastDeathPoint());
        assertEquals(payload, decoded);
    }

    @Test
    void unknownJsonPropertiesAreToleratedOnRead() {
        // a newer writer adding a field must not break older readers during rolling upgrades
        var json = """
                {
                  "format": 1,
                  "captured_at_epoch_milli": 1763456789000,
                  "position": {
                    "world": "survival",
                    "environment": "NORMAL",
                    "x": 1.5, "y": 64.0, "z": -2.5, "yaw": 90.0, "pitch": 0.0,
                    "bed_rock_extra": "from-a-future-build"
                  },
                  "future_top_level_field": {"nested": [1, 2, 3]}
                }
                """;
        LocationPayload decoded = MAPPER.treeToValue(MAPPER.readTree(json), LocationPayload.class);
        assertEquals(1, decoded.format());
        assertEquals("survival", decoded.position().world());
        assertNull(decoded.respawnPoint());
        assertNull(decoded.lastDeathPoint());
        assertTrue(LocationValidation.check(decoded).valid());
    }

    @Test
    void missingPositionDecodesWithoutThrowingThenFailsValidation() {
        // corrupt-but-well-formed JSON must surface as a validation failure, never as a codec exception
        var json = """
                { "format": 1, "captured_at_epoch_milli": 1763456789000 }
                """;
        LocationPayload decoded = MAPPER.treeToValue(MAPPER.readTree(json), LocationPayload.class);
        assertNull(decoded.position());
        assertFalse(LocationValidation.check(decoded).valid());
    }

    @Test
    void anchorRespawnPointRoundTripsThroughTheSingleRespawnSection() {
        // the platform stores bed and respawn anchor in one slot; the snapshot must carry
        // exactly one respawn section and no anchor-specific field, or a rolling upgrade
        // between builds that grew an anchor field would fork the contract
        JsonNode tree = MAPPER.valueToTree(fullPayload());
        assertTrue(tree.has("respawn_point"));
        assertFalse(tree.has("anchor"));
        assertFalse(tree.has("bed_spawn"));
        assertFalse(tree.has("respawn_anchor"));
        assertFalse(tree.has("anchor_respawn_point"));

        LocationPayload decoded = MAPPER.treeToValue(tree, LocationPayload.class);
        assertEquals("survival", decoded.respawnPoint().world());
        assertEquals("NORMAL", decoded.respawnPoint().environment());
    }

    @Test
    void negativeCaptureTimeSurvivesTheRoundTripForValidationToReject() {
        // validation, not the codec, owns the captured_at_epoch_milli sanity check
        var payload = new LocationPayload(LocationPayload.FORMAT, -1L,
                snapshot("survival", "NORMAL", 0, 64, 0), null, null);
        LocationPayload decoded = MAPPER.treeToValue(MAPPER.valueToTree(payload), LocationPayload.class);
        assertFalse(LocationValidation.check(decoded).valid());
    }
}
