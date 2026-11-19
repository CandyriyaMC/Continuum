package lol.candyriya.continuum.paper.modules.location;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Structural checks on a decoded location payload, shared between the module's {@code validate}
 * step and the restore path's last-line defense. Pure — no platform types, no I/O.
 *
 * <p>Deliberately shallow: structure, finiteness and generous magnitude bounds only. Whether a
 * world exists on this node is a restore-time concern (skip, never a validation failure) —
 * an unknown world in the snapshot is a legitimate state, not a corrupt one.</p>
 */
public final class LocationValidation {

    /**
     * Generous coordinate cap, well above the vanilla world border (29 999 984) and high enough
     * that only absurd, corrupt data trips it.
     */
    public static final double MAX_COORDINATE = 30_000_000.0;

    private LocationValidation() {
    }

    /**
     * True when every coordinate of {@code snapshot} is within {@link #MAX_COORDINATE}.
     * A null snapshot answers false (callers skip the section instead of trusting it).
     */
    public static boolean withinBounds(@Nullable PositionSnapshot snapshot) {
        return snapshot != null
                && Math.abs(snapshot.x()) <= MAX_COORDINATE
                && Math.abs(snapshot.y()) <= MAX_COORDINATE
                && Math.abs(snapshot.z()) <= MAX_COORDINATE;
    }

    /**
     * Checks {@code data} for structural sanity.
     *
     * @param data the decoded payload, never null
     * @return {@link ValidationResult#ok()} when the payload may be restored, otherwise an
     *         invalid result with an English reason
     */
    public static @NotNull ValidationResult check(@NotNull LocationPayload data) {
        if (data.format() != LocationPayload.FORMAT) {
            return ValidationResult.invalid("unsupported location payload format " + data.format()
                    + " (expected " + LocationPayload.FORMAT + ")");
        }
        if (data.capturedAtEpochMilli() < 0) {
            return ValidationResult.invalid("captured_at_epoch_milli is negative (" + data.capturedAtEpochMilli() + ")");
        }
        PositionSnapshot position = data.position();
        if (position == null) {
            return ValidationResult.invalid("position section is missing — the payload cannot place the player");
        }
        ValidationResult positionCheck = checkSection("position", position);
        if (!positionCheck.valid()) {
            return positionCheck;
        }
        ValidationResult respawnCheck = checkSection("respawn point", data.respawnPoint());
        if (!respawnCheck.valid()) {
            return respawnCheck;
        }
        return checkSection("last death point", data.lastDeathPoint());
    }

    private static @NotNull ValidationResult checkSection(String name, @Nullable PositionSnapshot snapshot) {
        if (snapshot == null) {
            return ValidationResult.ok();
        }
        if (snapshot.world().isBlank()) {
            return ValidationResult.invalid(name + " section has a blank world name");
        }
        if (!withinBounds(snapshot)) {
            return ValidationResult.invalid(name + " section coordinate out of bounds (max ±"
                    + (long) MAX_COORDINATE + " per axis)");
        }
        return ValidationResult.ok();
    }
}
