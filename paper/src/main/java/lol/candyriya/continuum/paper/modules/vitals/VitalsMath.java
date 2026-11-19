package lol.candyriya.continuum.paper.modules.vitals;

/**
 * Pure value math for vitals restore. Total functions: no NaN/Infinity ever
 * reaches a setter (Bukkit's preconditions would throw), and no stored value
 * can push live state out of its sane range.
 *
 * <p>These are the last line of defense. Validation rejects non-finite and
 * negative input up front; these helpers make the restore path itself
 * throw-free even for an unvalidated payload.</p>
 */
final class VitalsMath {

    /** Vanilla default max health; used when the live attribute is missing or broken. */
    static final double DEFAULT_MAX_HEALTH = 20.0;

    private VitalsMath() {
        // static access only
    }

    static int resolveFoodLevel(int stored) {
        return clampInt(stored, 0, 20);
    }

    /**
     * Saturation is capped by the food level the player will end up with after
     * the food write — anything above it is meaningless in vanilla.
     * Non-finite input resolves to the nearest bound (NaN to 0).
     */
    static float resolveSaturation(float stored, int restoredFoodLevel) {
        if (Float.isNaN(stored)) {
            return 0.0f;
        }
        return clampFloat(stored, 0.0f, Math.max(restoredFoodLevel, 0));
    }

    /** Passed through above 0 — mods may use scales beyond vanilla's 4. */
    static float resolveExhaustion(float stored) {
        if (Float.isNaN(stored) || Float.isInfinite(stored)) {
            return 0.0f;
        }
        return Math.max(stored, 0.0f);
    }

    static double resolveAbsorption(double stored) {
        if (Double.isNaN(stored) || Double.isInfinite(stored)) {
            return 0.0;
        }
        return Math.max(stored, 0.0);
    }

    static int resolveAir(int stored) {
        return Math.max(stored, 0);
    }

    static int resolveFireTicks(int stored, int liveMax) {
        return resolveTicks(stored, liveMax);
    }

    static int resolveFreezeTicks(int stored, int liveMax) {
        return resolveTicks(stored, liveMax);
    }

    private static int resolveTicks(int stored, int liveMax) {
        if (stored <= 0) {
            return 0;
        }
        // a live max below 1 means the platform did not report one; do not invent an upper bound
        int ceiling = liveMax >= 1 ? liveMax : Integer.MAX_VALUE;
        return Math.min(stored, ceiling);
    }

    /**
     * Current health may never exceed the effective max health the player has
     * <em>after</em> the attributes module restored it — writing past it throws
     * inside the platform layer.
     *
     * <p>A ceiling of 0 or NaN (broken attribute cascade) resolves to the
     * vanilla default instead: falling back to 0 would read as "kill the
     * player", and a half-restored attribute set must not be fatal.</p>
     */
    static double resolveHealth(double stored, double effectiveMax) {
        double ceiling = effectiveMax;
        if (!(ceiling > 0.0)) {
            ceiling = DEFAULT_MAX_HEALTH;
        }
        if (Double.isNaN(stored)) {
            return 0.0;
        }
        return clampDouble(stored, 0.0, ceiling);
    }

    static float resolveFallDistance(float stored) {
        if (Float.isNaN(stored) || Float.isInfinite(stored)) {
            return 0.0f;
        }
        return Math.max(stored, 0.0f);
    }

    static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
