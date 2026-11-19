package lol.candyriya.continuum.paper.modules.experience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The invariant this module exists for: restoring never loses partial progress.
 */
class ExperienceMathInvariantTest {

    @Test
    void progressSurvivesBitExactAcrossLevels() {
        // every level 0..80 with awkward progress fractions — none may be quantized,
        // normalized or dropped
        float[] tricky = {0.0f, 0.1f, 0.25f, 0.42f, 1.0f / 3.0f, 0.5f, 0.99f, 1.0f};
        for (int level = 0; level <= 80; level++) {
            for (float progress : tricky) {
                ExperiencePayload payload = new ExperiencePayload(ExperiencePayload.FORMAT, level, progress, 1234 + level);
                assertEquals(Float.floatToIntBits(progress),
                        Float.floatToIntBits(ExperienceMath.resolveProgress(payload.progress())),
                        "level " + level + " lost progress bits for " + progress);
                assertEquals(level, ExperienceMath.resolveLevel(payload.level()));
                assertEquals(1234 + level, ExperienceMath.resolveTotal(payload.totalExperience()));
            }
        }
    }

    @Test
    void progressIsClampedIntoClosedRange() {
        assertEquals(0.0f, ExperienceMath.resolveProgress(-0.001f));
        assertEquals(0.0f, ExperienceMath.resolveProgress(Float.NaN));
        assertEquals(0.0f, ExperienceMath.resolveProgress(Float.NEGATIVE_INFINITY));
        assertEquals(1.0f, ExperienceMath.resolveProgress(1.000001f));
        assertEquals(1.0f, ExperienceMath.resolveProgress(Float.POSITIVE_INFINITY));
        assertEquals(0.42f, ExperienceMath.resolveProgress(0.42f));
        assertEquals(1.0f, ExperienceMath.resolveProgress(1.0f), "a pending level-up at exactly 1.0 must survive");
    }

    @Test
    void negativeLevelAndTotalCollapseToZero() {
        assertEquals(0, ExperienceMath.resolveLevel(-1));
        assertEquals(0, ExperienceMath.resolveLevel(Integer.MIN_VALUE));
        assertEquals(0, ExperienceMath.resolveTotal(-1));
        assertEquals(0, ExperienceMath.resolveTotal(Integer.MIN_VALUE));
    }

    @Test
    void levelWithIdenticalInputIsIdempotent() {
        int level = 37;
        float progress = 0.42f;
        int total = 999;
        assertEquals(level, ExperienceMath.resolveLevel(ExperienceMath.resolveLevel(level)));
        assertEquals(Float.floatToIntBits(progress), Float.floatToIntBits(ExperienceMath.resolveProgress(ExperienceMath.resolveProgress(progress))));
        assertEquals(total, ExperienceMath.resolveTotal(ExperienceMath.resolveTotal(total)));
    }
}
