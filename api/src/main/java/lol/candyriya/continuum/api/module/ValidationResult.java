package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of {@link SyncModule#validate(Object, Object)}.
 *
 * <p>Validation answers one question: is this stored payload sane enough to apply to a
 * live player? It is the last gate before the module mutates game state, so it should be
 * cheap and defensive — check structure and ranges, not semantics that require a full
 * deserialize.</p>
 *
 * <p>An invalid result does <em>not</em> silently skip the module. Continuum marks the
 * module {@code VALIDATION_FAILED} in the report; whether the operation then continues or
 * aborts is governed by the module's {@link FailurePolicy}.</p>
 *
 * <p>Instances are created through {@link #ok()} and {@link #invalid(String)}; the record
 * components are normalized in the compact constructor (a valid result never carries a
 * reason, an invalid one always does).</p>
 *
 * @param valid whether the data may be applied, true = apply it
 * @param reason why the data was rejected, null when {@code valid} is true; mandatory otherwise
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record ValidationResult(boolean valid, @Nullable String reason) {

    /**
     * Compact constructor: a valid result drops any reason; an invalid one requires a
     * non-blank reason.
     *
     * @throws IllegalArgumentException when {@code valid} is false and {@code reason} is null or blank
     */
    public ValidationResult {
        if (valid) {
            reason = null;
        } else if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an invalid ValidationResult must carry a non-blank reason");
        }
    }

    /**
     * The data is fine.
     *
     * @return a valid result
     */
    @NotNull
    public static ValidationResult ok() {
        return new ValidationResult(true, null);
    }

    /**
     * The data is rejected.
     *
     * @param reason what is wrong with it, in English; must not be blank
     * @return an invalid result carrying the reason
     * @throws IllegalArgumentException when {@code reason} is null or blank
     */
    @NotNull
    public static ValidationResult invalid(@NotNull String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be null or blank");
        }
        return new ValidationResult(false, reason);
    }
}
