package lol.candyriya.continuum.paper.modules.experience;

import lol.candyriya.continuum.paper.modules.vitals.JacksonCodecBridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the experience payload, driven through the same mapper
 * the production snapshot pipeline builds (see {@link JacksonCodecBridge}).
 */
class ExperiencePayloadCodecTest {

    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    @Test
    void roundTripPreservesLevelAndTotalAndProgressBits() {
        ExperiencePayload original = new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 1234);
        ExperiencePayload decoded = codec.roundTrip(original, ExperiencePayload.class);

        assertEquals(original.level(), decoded.level());
        assertEquals(original.totalExperience(), decoded.totalExperience());
        assertEquals(Float.floatToIntBits(original.progress()), Float.floatToIntBits(decoded.progress()));
    }

    @Test
    void zeroIsZeroAndProgressBitsSurvive() {
        for (float progress : new float[]{0.0f, 0.1f, 0.42f, 1.0f / 3.0f, 0.5f, 1.0f}) {
            ExperiencePayload payload = new ExperiencePayload(ExperiencePayload.FORMAT, 0, progress, 0);
            ExperiencePayload decoded = codec.roundTrip(payload, ExperiencePayload.class);

            assertEquals(Float.floatToIntBits(progress), Float.floatToIntBits(decoded.progress()),
                    "progress bits drifted for " + progress);
        }
    }

    @Test
    void wireFormatUsesSnakeCaseKeysAndFormatMarker() {
        String json = codec.toJson(new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 1234));

        assertTrue(json.contains("\"format\":1"), json);
        assertTrue(json.contains("\"level\":30"), json);
        assertTrue(json.contains("\"progress\":0.42"), json);
        assertTrue(json.contains("\"total_experience\":1234"), json);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("totalExperience"), json);
    }
}
