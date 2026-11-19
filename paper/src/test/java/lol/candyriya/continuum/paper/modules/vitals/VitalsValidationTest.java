package lol.candyriya.continuum.paper.modules.vitals;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VitalsValidationTest {

    private static VitalsPayload valid() {
        return new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
    }

    @Test
    void validPayloadPasses() {
        assertTrue(VitalsValidation.validate(valid()).valid());
    }

    @Test
    void nullFallDistanceIsAllowed() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 20.0, "minecraft:max_health",
                20.0, 0.0, 20, 5.0f, 0.0f, 300, 0, 0, null);
        assertTrue(VitalsValidation.validate(payload).valid());
    }

    @Test
    void nullPayloadIsInvalid() {
        assertFalse(VitalsValidation.validate(null).valid());
    }

    @Test
    void unknownFormatIsInvalid() {
        VitalsPayload payload = new VitalsPayload(2, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(payload).valid());
        assertTrue(VitalsValidation.validate(payload).reason().contains("format"));
    }

    @Test
    void negativeOrNonFiniteHealthIsInvalid() {
        assertFalse(validateHealth(-0.5));
        assertFalse(validateHealth(Double.NaN));
        assertFalse(validateHealth(Double.POSITIVE_INFINITY));
        assertFalse(validateHealth(Double.NEGATIVE_INFINITY));
    }

    private static boolean validateHealth(double health) {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, health, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        return VitalsValidation.validate(payload).valid();
    }

    @Test
    void blankAttributeKeyIsInvalid() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "  ",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(payload).valid());
    }

    @Test
    void invalidMaxHealthBaseIsInvalid() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                -1.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(payload).valid());
        VitalsPayload nan = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                Double.NaN, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(nan).valid());
    }

    @Test
    void negativeAbsorptionIsInvalid() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, -0.1, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(payload).valid());
    }

    @Test
    void foodLevelOutOfRangeIsInvalid() {
        assertFalse(validateFoodLevel(-1));
        assertFalse(validateFoodLevel(21));
        assertTrue(validateFoodLevel(0));
        assertTrue(validateFoodLevel(20));
    }

    private static boolean validateFoodLevel(int foodLevel) {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, foodLevel, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        return VitalsValidation.validate(payload).valid();
    }

    @Test
    void saturationAboveFoodLevelIsAllowed() {
        // incoherent but harmless — the restore clamps instead of voiding the payload
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 5, 12.0f, 2.5f, 187, 0, 0, 1.75f);
        assertTrue(VitalsValidation.validate(payload).valid());
    }

    @Test
    void exhaustionAboveVanillaCeilingIsAllowed() {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 9.0f, 187, 0, 0, 1.75f);
        assertTrue(VitalsValidation.validate(payload).valid());
    }

    @Test
    void negativeOrNonFiniteSaturationAndExhaustionAreInvalid() {
        VitalsPayload negativeSat = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, -0.5f, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(negativeSat).valid());
        VitalsPayload nanSat = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, Float.NaN, 2.5f, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(nanSat).valid());
        VitalsPayload nanExhaustion = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, Float.NaN, 187, 0, 0, 1.75f);
        assertFalse(VitalsValidation.validate(nanExhaustion).valid());
    }

    @Test
    void negativeAirAndTicksAreInvalid() {
        assertFalse(validateAir(-1));
        assertFalse(validateFire(-1));
        assertFalse(validateFreeze(-1));
        assertTrue(validateAir(0));
        assertTrue(validateFire(0));
        assertTrue(validateFreeze(0));
    }

    private static boolean validateAir(int air) {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, air, 0, 0, 1.75f);
        return VitalsValidation.validate(payload).valid();
    }

    private static boolean validateFire(int fire) {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, fire, 0, 1.75f);
        return VitalsValidation.validate(payload).valid();
    }

    private static boolean validateFreeze(int freeze) {
        VitalsPayload payload = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, freeze, 1.75f);
        return VitalsValidation.validate(payload).valid();
    }

    @Test
    void invalidFallDistanceIsInvalid() {
        VitalsPayload negative = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, -0.5f);
        assertFalse(VitalsValidation.validate(negative).valid());
        VitalsPayload nan = new VitalsPayload(VitalsPayload.FORMAT, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, Float.NaN);
        assertFalse(VitalsValidation.validate(nan).valid());
    }

    @Test
    void validationResultAlwaysCarriesReasonWhenInvalid() {
        VitalsPayload payload = new VitalsPayload(2, 17.5, "minecraft:max_health",
                20.0, 6.0, 17, 9.5f, 2.5f, 187, 0, 0, 1.75f);
        ValidationResult result = VitalsValidation.validate(payload);
        assertFalse(result.valid());
        assertTrue(result.reason() != null && !result.reason().isBlank());
    }
}
