package lol.candyriya.continuum.paper.modules.vitals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the vitals payload, driven through the same mapper the
 * production snapshot pipeline builds (see {@link JacksonCodecBridge}).
 */
class VitalsPayloadCodecTest {

    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    private static VitalsPayload sample() {
        return new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 10, 30, 1.75f);
    }

    @Test
    void roundTripPreservesEveryField() {
        VitalsPayload original = sample();
        VitalsPayload decoded = codec.roundTrip(original, VitalsPayload.class);

        assertEquals(original, decoded);
        assertEquals(Float.floatToIntBits(original.saturation()), Float.floatToIntBits(decoded.saturation()));
        assertEquals(Float.floatToIntBits(original.exhaustion()), Float.floatToIntBits(decoded.exhaustion()));
        assertEquals(Float.floatToIntBits(original.fallDistance()), Float.floatToIntBits(decoded.fallDistance()));
    }

    @Test
    void floatsSurviveShortestRoundTrip() {
        // the values most likely to drift if anything re-serializes via decimal formatting
        for (float tricky : new float[]{0.1f, 0.42f, 1.0f / 3.0f, 3.14159274f, 1.17549435e-38f}) {
            VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                    20.0, 6.0, 17, tricky, tricky, 187, 0, 0, tricky);
            VitalsPayload decoded = codec.roundTrip(payload, VitalsPayload.class);

            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(decoded.saturation()), "saturation drifted for " + tricky);
            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(decoded.exhaustion()), "exhaustion drifted for " + tricky);
            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(decoded.fallDistance()), "fall distance drifted for " + tricky);
        }
    }

    @Test
    void nullFallDistanceRoundTrips() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, null);

        assertEquals(payload, codec.roundTrip(payload, VitalsPayload.class));
    }

    @Test
    void wireFormatUsesSnakeCaseKeysAndFormatMarker() {
        String json = codec.toJson(sample());

        assertTrue(json.contains("\"format\":1"), json);
        assertTrue(json.contains("\"max_health_attribute\":\"minecraft:max_health\""), json);
        assertTrue(json.contains("\"max_health_base\":20.0"), json);
        assertTrue(json.contains("\"food_level\":17"), json);
        assertTrue(json.contains("\"remaining_air\":187"), json);
        assertTrue(json.contains("\"fire_ticks\":10"), json);
        assertTrue(json.contains("\"freeze_ticks\":30"), json);
        assertTrue(json.contains("\"fall_distance\":1.75"), json);
        // camelCase keys must not leak into the wire format
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("maxHealthAttribute"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("fallDistance"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("foodLevel"), json);
    }
}
