package lol.candyriya.continuum.paper.modules.gamemode;

import lol.candyriya.continuum.paper.modules.vitals.JacksonCodecBridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the gamemode payload, driven through the same mapper
 * the production snapshot pipeline builds (see {@link JacksonCodecBridge}).
 */
class GameModePayloadCodecTest {

    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    private static GameModePayload sample() {
        return new GameModePayload(GameModePayload.FORMAT, "CREATIVE",
                true, true, 0.35f, 0.45f, true, false, true);
    }

    @Test
    void roundTripPreservesEveryField() {
        GameModePayload original = sample();
        GameModePayload decoded = codec.roundTrip(original, GameModePayload.class);

        assertEquals(original, decoded);
        assertEquals(Float.floatToIntBits(original.flySpeed()), Float.floatToIntBits(decoded.flySpeed()));
        assertEquals(Float.floatToIntBits(original.walkSpeed()), Float.floatToIntBits(decoded.walkSpeed()));
    }

    @Test
    void speedBitsSurviveRoundTrip() {
        for (float tricky : new float[]{0.1f, 0.2f, 0.35f, -0.5f, 1.0f, -1.0f, 1.0f / 3.0f}) {
            GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                    true, false, tricky, tricky, false, false, false);
            GameModePayload decoded = codec.roundTrip(payload, GameModePayload.class);

            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(decoded.flySpeed()), "fly speed drifted for " + tricky);
            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(decoded.walkSpeed()), "walk speed drifted for " + tricky);
        }
    }

    @Test
    void wireFormatUsesSnakeCaseKeysAndFormatMarker() {
        String json = codec.toJson(sample());

        assertTrue(json.contains("\"format\":1"), json);
        assertTrue(json.contains("\"game_mode\":\"CREATIVE\""), json);
        assertTrue(json.contains("\"allow_flight\":true"), json);
        assertTrue(json.contains("\"flying\":true"), json);
        assertTrue(json.contains("\"fly_speed\":0.35"), json);
        assertTrue(json.contains("\"walk_speed\":0.45"), json);
        assertTrue(json.contains("\"gliding\":true"), json);
        assertTrue(json.contains("\"sneaking\":false"), json);
        assertTrue(json.contains("\"sprinting\":true"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("gameMode"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("allowFlight"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("flySpeed"), json);
    }

    @Test
    void gameModeIsStoredAsEnumName() {
        // the wire value must be the plain constant name, never an ordinal or a class tag
        String json = codec.toJson(sample());

        assertTrue(json.contains("\"game_mode\":\"CREATIVE\""), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("\"game_mode\":0"), json);
    }
}
