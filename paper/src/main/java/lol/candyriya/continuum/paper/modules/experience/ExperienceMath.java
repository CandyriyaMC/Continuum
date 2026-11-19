package lol.candyriya.continuum.paper.modules.experience;

/**
 * Pure value math for experience restore. Total functions, like
 * {@code VitalsMath}: validation rejects the unrecoverable input up front, and
 * these keep the setters throw-free even for an unvalidated payload.
 *
 * <p>Progress is preserved bit-exact whenever it is finite — the restore does
 * not quantize, normalize or derive it, so a level never absorbs its own
 * partial progress.</p>
 */
final class ExperienceMath {

    private ExperienceMath() {
        // static access only
    }

    static int resolveLevel(int stored) {
        return Math.max(stored, 0);
    }

    static int resolveTotal(int stored) {
        return Math.max(stored, 0);
    }

    /**
     * Non-finite input resolves to the nearest bound (NaN has no ordering, so
     * it collapses to 0); finite values are clamped into the closed range.
     * Progress is never quantized, normalized or derived — a level never
     * absorbs its own fractional part.
     */
    static float resolveProgress(float stored) {
        if (Float.isNaN(stored)) {
            return 0.0f;
        }
        return clamp(stored, 0.0f, 1.0f);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
