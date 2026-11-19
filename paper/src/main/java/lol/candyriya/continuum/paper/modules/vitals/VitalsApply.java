package lol.candyriya.continuum.paper.modules.vitals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a validated {@link VitalsPayload} in the documented order:
 *
 * <ol>
 *   <li>effective max health is read (the attributes module restores it before
 *       this module runs — see {@code dependencies()}),</li>
 *   <li>food level, saturation (capped by the restored food level), exhaustion
 *       — food first so regeneration sees a consistent pair,</li>
 *   <li>remaining air, fire ticks, freeze ticks — atmosphere before health,</li>
 *   <li>current health, clamped to the effective max — never above it,</li>
 *   <li>absorption — deliberately after health, so a death caused by the health
 *       write can never leave absorption hearts behind on a corpse,</li>
 *   <li>fall distance (only when the module was built with fall distance
 *       capture enabled) — value only, never a teleport.</li>
 * </ol>
 *
 * <p>All writes are absolute values, so applying the same payload twice is
 * idempotent.</p>
 */
final class VitalsApply {

    private static final Logger LOGGER = LoggerFactory.getLogger(VitalsApply.class);

    private VitalsApply() {
        // static access only
    }

    static void apply(VitalsPayload data, VitalsOps ops, boolean restoreFallDistance) {
        double ceiling = ops.maxHealth();
        if (!(ceiling > 0.0)) {
            // broken attribute cascade (0 or NaN) — restoring health 0 would read as "kill the player"
            LOGGER.warn("effective max health is {} after attribute restore; falling back to {}", ceiling, VitalsMath.DEFAULT_MAX_HEALTH);
            ceiling = VitalsMath.DEFAULT_MAX_HEALTH;
        }

        int food = VitalsMath.resolveFoodLevel(data.foodLevel());
        ops.setFoodLevel(food);
        ops.setSaturation(VitalsMath.resolveSaturation(data.saturation(), food));
        ops.setExhaustion(VitalsMath.resolveExhaustion(data.exhaustion()));

        ops.setRemainingAir(VitalsMath.resolveAir(data.remainingAir()));
        ops.setFireTicks(VitalsMath.resolveFireTicks(data.fireTicks(), ops.maxFireTicks()));
        ops.setFreezeTicks(VitalsMath.resolveFreezeTicks(data.freezeTicks(), ops.maxFreezeTicks()));

        ops.setHealth(VitalsMath.resolveHealth(data.health(), ceiling));
        ops.setAbsorptionAmount(VitalsMath.resolveAbsorption(data.absorption()));

        if (restoreFallDistance && data.fallDistance() != null) {
            ops.setFallDistance(VitalsMath.resolveFallDistance(data.fallDistance()));
        }
    }
}
