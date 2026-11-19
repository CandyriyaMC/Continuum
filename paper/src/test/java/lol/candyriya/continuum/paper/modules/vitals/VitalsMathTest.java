package lol.candyriya.continuum.paper.modules.vitals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VitalsMathTest {

    @Test
    void foodLevelClampsToVanillaRange() {
        assertEquals(0, VitalsMath.resolveFoodLevel(-3));
        assertEquals(0, VitalsMath.resolveFoodLevel(0));
        assertEquals(17, VitalsMath.resolveFoodLevel(17));
        assertEquals(20, VitalsMath.resolveFoodLevel(20));
        assertEquals(20, VitalsMath.resolveFoodLevel(99));
    }

    @Test
    void saturationClampsToRestoredFoodLevel() {
        // negative and NaN zero out; above the restored food level is meaningless in vanilla
        assertEquals(0.0f, VitalsMath.resolveSaturation(-1.0f, 20));
        assertEquals(0.0f, VitalsMath.resolveSaturation(Float.NaN, 20));
        assertEquals(20.0f, VitalsMath.resolveSaturation(Float.POSITIVE_INFINITY, 20));
        assertEquals(0.0f, VitalsMath.resolveSaturation(Float.NEGATIVE_INFINITY, 20));
        assertEquals(9.5f, VitalsMath.resolveSaturation(9.5f, 20));
        assertEquals(7.0f, VitalsMath.resolveSaturation(9.5f, 7));
        assertEquals(0.0f, VitalsMath.resolveSaturation(9.5f, 0));
    }

    @Test
    void exhaustionPassesThroughAboveZero() {
        // mods may run wider scales — no upper clamp, only the non-negative floor
        assertEquals(0.0f, VitalsMath.resolveExhaustion(-0.5f));
        assertEquals(0.0f, VitalsMath.resolveExhaustion(Float.NaN));
        assertEquals(2.5f, VitalsMath.resolveExhaustion(2.5f));
        assertEquals(9.0f, VitalsMath.resolveExhaustion(9.0f));
    }

    @Test
    void absorptionNeverGoesNegative() {
        assertEquals(0.0, VitalsMath.resolveAbsorption(-4.0));
        assertEquals(0.0, VitalsMath.resolveAbsorption(Double.NaN));
        assertEquals(0.0, VitalsMath.resolveAbsorption(Double.POSITIVE_INFINITY));
        assertEquals(13.0, VitalsMath.resolveAbsorption(13.0));
    }

    @Test
    void airNeverGoesNegative() {
        assertEquals(0, VitalsMath.resolveAir(-1));
        assertEquals(0, VitalsMath.resolveAir(0));
        assertEquals(187, VitalsMath.resolveAir(187));
        assertEquals(1000, VitalsMath.resolveAir(1000), "air has no artificial upper bound");
    }

    @Test
    void ticksClampToLiveMaxWhenReported() {
        assertEquals(0, VitalsMath.resolveFireTicks(-5, 20));
        assertEquals(0, VitalsMath.resolveFireTicks(0, 20));
        assertEquals(10, VitalsMath.resolveFireTicks(10, 20));
        assertEquals(20, VitalsMath.resolveFireTicks(30, 20));

        assertEquals(0, VitalsMath.resolveFreezeTicks(-5, 140));
        assertEquals(140, VitalsMath.resolveFreezeTicks(200, 140));
        assertEquals(77, VitalsMath.resolveFreezeTicks(77, 140));
    }

    @Test
    void ticksPassThroughWhenNoLiveMaxReported() {
        assertEquals(5_000, VitalsMath.resolveFireTicks(5_000, 0));
        assertEquals(5_000, VitalsMath.resolveFreezeTicks(5_000, -1));
    }

    @Test
    void healthClampsToEffectiveMax() {
        assertEquals(17.5, VitalsMath.resolveHealth(17.5, 40.0));
        assertEquals(40.0, VitalsMath.resolveHealth(55.0, 40.0));
        assertEquals(0.0, VitalsMath.resolveHealth(-1.0, 40.0));
        assertEquals(20.0, VitalsMath.resolveHealth(99.0, 20.0));
    }

    @Test
    void healthZeroIsAllowedAndStaysZero() {
        // a dead snapshot restores dead, it does not resurrect at half health
        assertEquals(0.0, VitalsMath.resolveHealth(0.0, 40.0));
    }

    @Test
    void healthWithBrokenCeilingFallsBackToVanillaDefault() {
        // effective max 0 or NaN (broken attribute cascade) must not read as "kill the player"
        assertEquals(20.0, VitalsMath.resolveHealth(99.0, 0.0));
        assertEquals(20.0, VitalsMath.resolveHealth(99.0, Double.NaN));
        assertEquals(20.0, VitalsMath.resolveHealth(30.0, -5.0));
        assertEquals(0.0, VitalsMath.resolveHealth(0.0, 0.0), "dead is still dead");
    }

    @Test
    void nonFiniteHealthClampsToBounds() {
        assertEquals(0.0, VitalsMath.resolveHealth(Double.NaN, 20.0));
        assertEquals(0.0, VitalsMath.resolveHealth(Double.NEGATIVE_INFINITY, 20.0));
        assertEquals(20.0, VitalsMath.resolveHealth(Double.POSITIVE_INFINITY, 20.0));
    }

    @Test
    void fallDistanceNeverGoesNegative() {
        assertEquals(0.0f, VitalsMath.resolveFallDistance(-1.0f));
        assertEquals(0.0f, VitalsMath.resolveFallDistance(Float.NaN));
        assertEquals(0.0f, VitalsMath.resolveFallDistance(Float.POSITIVE_INFINITY));
        assertEquals(1.75f, VitalsMath.resolveFallDistance(1.75f));
    }
}
