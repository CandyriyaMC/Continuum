package lol.candyriya.continuum.paper.modules.vitals;

import lol.candyriya.continuum.api.module.ValidationResult;

/**
 * Defensive checks for {@link VitalsPayload} before restore applies it.
 *
 * <p>Deliberately structural: range checks that can throw inside the platform
 * layer, plus corruption signals. Values that are quirky but safe to apply
 * (saturation above food level, exhaustion above 4, absorption above any
 * ceiling) are <em>not</em> rejected here — the restore path clamps or passes
 * them through, and rejecting them would void an otherwise good payload.</p>
 */
final class VitalsValidation {

    private VitalsValidation() {
        // static access only
    }

    static ValidationResult validate(VitalsPayload data) {
        if (data == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (data.format() != VitalsPayload.FORMAT) {
            return ValidationResult.invalid(
                    "unsupported payload format " + data.format() + " (expected " + VitalsPayload.FORMAT + ")");
        }
        if (!finite(data.health())) {
            return ValidationResult.invalid("health is not finite: " + data.health());
        }
        if (data.health() < 0.0) {
            return ValidationResult.invalid("health is negative: " + data.health());
        }
        if (data.maxHealthAttribute() == null || data.maxHealthAttribute().isBlank()) {
            return ValidationResult.invalid("max health attribute key is null or blank");
        }
        if (!finite(data.maxHealthBase()) || data.maxHealthBase() < 0.0) {
            return ValidationResult.invalid("max health attribute base is not a finite non-negative number: " + data.maxHealthBase());
        }
        if (!finite(data.absorption()) || data.absorption() < 0.0) {
            return ValidationResult.invalid("absorption is not a finite non-negative number: " + data.absorption());
        }
        if (data.foodLevel() < 0 || data.foodLevel() > 20) {
            return ValidationResult.invalid("food level out of range 0..20: " + data.foodLevel());
        }
        if (!finite(data.saturation()) || data.saturation() < 0.0f) {
            return ValidationResult.invalid("saturation is not a finite non-negative number: " + data.saturation());
        }
        if (!finite(data.exhaustion()) || data.exhaustion() < 0.0f) {
            return ValidationResult.invalid("exhaustion is not a finite non-negative number: " + data.exhaustion());
        }
        if (data.remainingAir() < 0) {
            return ValidationResult.invalid("remaining air is negative: " + data.remainingAir());
        }
        if (data.fireTicks() < 0) {
            return ValidationResult.invalid("fire ticks are negative: " + data.fireTicks());
        }
        if (data.freezeTicks() < 0) {
            return ValidationResult.invalid("freeze ticks are negative: " + data.freezeTicks());
        }
        Float fall = data.fallDistance();
        if (fall != null && (!finite(fall) || fall < 0.0f)) {
            return ValidationResult.invalid("fall distance is not a finite non-negative number: " + fall);
        }
        return ValidationResult.ok();
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
