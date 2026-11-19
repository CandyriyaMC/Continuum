package lol.candyriya.continuum.paper.modules.experience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceValidationTest {

    private static ExperiencePayload valid() {
        return new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 1234);
    }

    @Test
    void validPayloadPasses() {
        assertTrue(ExperienceValidation.validate(valid()).valid());
    }

    @Test
    void nullPayloadIsInvalid() {
        assertFalse(ExperienceValidation.validate(null).valid());
    }

    @Test
    void unknownFormatIsInvalid() {
        ExperiencePayload payload = new ExperiencePayload(2, 30, 0.42f, 1234);
        assertFalse(ExperienceValidation.validate(payload).valid());
    }

    @Test
    void negativeLevelIsInvalid() {
        assertFalse(ExperienceValidation.validate(new ExperiencePayload(ExperiencePayload.FORMAT, -1, 0.42f, 1234)).valid());
        assertTrue(ExperienceValidation.validate(new ExperiencePayload(ExperiencePayload.FORMAT, 0, 0.42f, 1234)).valid());
    }

    @Test
    void negativeTotalIsInvalid() {
        assertFalse(ExperienceValidation.validate(new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, -1)).valid());
        assertTrue(ExperienceValidation.validate(new ExperiencePayload(ExperiencePayload.FORMAT, 30, 0.42f, 0)).valid());
    }

    @Test
    void progressOutOfRangeIsInvalid() {
        assertFalse(validateProgress(-0.001f));
        assertFalse(validateProgress(1.001f));
        assertFalse(validateProgress(Float.NaN));
        assertFalse(validateProgress(Float.POSITIVE_INFINITY));
        assertFalse(validateProgress(Float.NEGATIVE_INFINITY));
        assertTrue(validateProgress(0.0f));
        assertTrue(validateProgress(1.0f));
        assertTrue(validateProgress(0.5f));
    }

    private static boolean validateProgress(float progress) {
        return ExperienceValidation.validate(new ExperiencePayload(ExperiencePayload.FORMAT, 30, progress, 1234)).valid();
    }
}
