package lol.candyriya.continuum.paper.modules.effects;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.jetbrains.annotations.NotNull;

/**
 * Structural checks on a decoded effects payload, shared between the module's
 * {@code validate} step and anything else that wants to know whether a payload is
 * sane enough to apply. Pure — no platform types, no I/O.
 *
 * <p>This is deliberately shallow: structure and ranges only, not semantics that
 * require a registry lookup. Unknown types are a restore-time concern (skipped with
 * a warning), never a validation failure.</p>
 */
public final class EffectsValidation {

    private EffectsValidation() {
    }

    /**
     * Checks {@code data} for structural sanity.
     *
     * @param data the decoded payload, never null
     * @return {@link ValidationResult#ok()} when the payload may be applied, otherwise
     *         an invalid result with an English reason
     */
    public static @NotNull ValidationResult check(@NotNull EffectsPayload data) {
        if (data.format() != EffectsPayload.FORMAT) {
            return ValidationResult.invalid("unsupported effects payload format " + data.format()
                    + " (expected " + EffectsPayload.FORMAT + ")");
        }
        if (data.capturedAtEpochMilli() < 0) {
            return ValidationResult.invalid("captured_at_epoch_milli is negative (" + data.capturedAtEpochMilli() + ")");
        }
        if (data.effects().size() > EffectsPayload.MAX_EFFECTS) {
            return ValidationResult.invalid("effects list holds " + data.effects().size()
                    + " entries, max is " + EffectsPayload.MAX_EFFECTS);
        }
        return ValidationResult.ok();
    }
}
