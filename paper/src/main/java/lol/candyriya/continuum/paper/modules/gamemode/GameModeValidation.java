package lol.candyriya.continuum.paper.modules.gamemode;

import lol.candyriya.continuum.api.module.ValidationResult;

/**
 * Defensive checks for {@link GameModePayload} before restore applies it.
 *
 * <p>Non-finite speeds are hard failures — there is no meaningful value to
 * fall back to and the platform would throw. Finite out-of-range speeds are
 * <em>not</em> rejected: the restore clamps them into the platform range,
 * which loses far less than voiding the whole module over one stray float.
 * An unknown {@code game_mode} constant is likewise not rejected — the
 * restore skips just that field, so a snapshot from a newer server does not
 * hard-fail the rest of the payload.</p>
 */
final class GameModeValidation {

    private GameModeValidation() {
        // static access only
    }

    static ValidationResult validate(GameModePayload data) {
        if (data == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (data.format() != GameModePayload.FORMAT) {
            return ValidationResult.invalid(
                    "unsupported payload format " + data.format() + " (expected " + GameModePayload.FORMAT + ")");
        }
        if (data.gameMode() == null || data.gameMode().isBlank()) {
            return ValidationResult.invalid("game mode name is null or blank");
        }
        if (!finite(data.flySpeed())) {
            return ValidationResult.invalid("fly speed is not finite: " + data.flySpeed());
        }
        if (!finite(data.walkSpeed())) {
            return ValidationResult.invalid("walk speed is not finite: " + data.walkSpeed());
        }
        return ValidationResult.ok();
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
