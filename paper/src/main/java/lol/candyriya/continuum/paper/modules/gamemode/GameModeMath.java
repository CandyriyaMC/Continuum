package lol.candyriya.continuum.paper.modules.gamemode;

/**
 * Pure value math for gamemode restore. Total functions: validation and the
 * platform both throw on non-finite speeds, so nothing non-finite ever
 * reaches a setter.
 */
final class GameModeMath {

    /** Inclusive bounds of the flight/walk ability speed range on the platform. */
    static final float SPEED_MIN = -1.0f;
    static final float SPEED_MAX = 1.0f;

    private GameModeMath() {
        // static access only
    }

    /**
     * Clamps a finite speed into the platform's [-1.0, 1.0] range — vanilla's
     * negative values mean reverse-direction movement and are legitimate, so
     * the bound is the API's, not 0..1. Non-finite input clamps to the nearest
     * bound (NaN collapses to 0); validation rejects it before restore ever
     * sees it — this is only here so restore cannot throw.
     */
    static float resolveSpeed(float stored) {
        if (Float.isNaN(stored)) {
            return 0.0f;
        }
        return clamp(stored, SPEED_MIN, SPEED_MAX);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
