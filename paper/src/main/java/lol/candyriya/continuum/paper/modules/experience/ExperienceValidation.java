package lol.candyriya.continuum.paper.modules.experience;

import lol.candyriya.continuum.api.module.ValidationResult;

/**
 * Defensive checks for {@link ExperiencePayload} before restore applies it.
 *
 * <p>Every rule here maps to a hard platform precondition: Paper rejects an
 * out-of-range progress, a negative level and a negative total with
 * {@link IllegalArgumentException}. The one semantic rule — progress inside
 * the level is mandatory to carry — is structural in the payload itself, not
 * here.</p>
 */
final class ExperienceValidation {

    private ExperienceValidation() {
        // static access only
    }

    static ValidationResult validate(ExperiencePayload data) {
        if (data == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (data.format() != ExperiencePayload.FORMAT) {
            return ValidationResult.invalid(
                    "unsupported payload format " + data.format() + " (expected " + ExperiencePayload.FORMAT + ")");
        }
        if (data.level() < 0) {
            return ValidationResult.invalid("level is negative: " + data.level());
        }
        if (data.totalExperience() < 0) {
            return ValidationResult.invalid("total experience is negative: " + data.totalExperience());
        }
        float progress = data.progress();
        if (Float.isNaN(progress) || Float.isInfinite(progress)) {
            return ValidationResult.invalid("progress is not finite: " + progress);
        }
        if (progress < 0.0f || progress > 1.0f) {
            return ValidationResult.invalid("progress out of range 0.0..1.0: " + progress);
        }
        return ValidationResult.ok();
    }
}
