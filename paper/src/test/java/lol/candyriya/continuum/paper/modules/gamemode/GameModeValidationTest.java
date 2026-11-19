package lol.candyriya.continuum.paper.modules.gamemode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameModeValidationTest {

    private static GameModePayload valid() {
        return new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                false, false, 0.1f, 0.2f, false, false, true);
    }

    @Test
    void validPayloadPasses() {
        assertTrue(GameModeValidation.validate(valid()).valid());
    }

    @Test
    void nullPayloadIsInvalid() {
        assertFalse(GameModeValidation.validate(null).valid());
    }

    @Test
    void unknownFormatIsInvalid() {
        GameModePayload payload = new GameModePayload(2, "SURVIVAL",
                false, false, 0.1f, 0.2f, false, false, true);
        assertFalse(GameModeValidation.validate(payload).valid());
    }

    @Test
    void nullOrBlankGameModeIsInvalid() {
        GameModePayload nullMode = new GameModePayload(GameModePayload.FORMAT, null,
                false, false, 0.1f, 0.2f, false, false, true);
        assertFalse(GameModeValidation.validate(nullMode).valid());
        GameModePayload blankMode = new GameModePayload(GameModePayload.FORMAT, "  ",
                false, false, 0.1f, 0.2f, false, false, true);
        assertFalse(GameModeValidation.validate(blankMode).valid());
    }

    @Test
    void unknownGameModeConstantIsNotAValidationFailure() {
        // captured on a newer build — restore skips just the mode write
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "BUILD_MODE",
                false, false, 0.1f, 0.2f, false, false, true);
        assertTrue(GameModeValidation.validate(payload).valid());
    }

    @Test
    void nonFiniteSpeedsAreInvalid() {
        GameModePayload nanFly = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                false, false, Float.NaN, 0.2f, false, false, true);
        assertFalse(GameModeValidation.validate(nanFly).valid());
        GameModePayload infiniteWalk = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                false, false, 0.1f, Float.POSITIVE_INFINITY, false, false, true);
        assertFalse(GameModeValidation.validate(infiniteWalk).valid());
    }

    @Test
    void finiteOutOfRangeSpeedsAreNotAValidationFailure() {
        // clampable at restore — voiding the payload would lose game mode too
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "SURVIVAL",
                false, false, 3.7f, -2.0f, false, false, true);
        assertTrue(GameModeValidation.validate(payload).valid());
    }

    @Test
    void booleanFlagsAreUntouched() {
        GameModePayload payload = new GameModePayload(GameModePayload.FORMAT, "CREATIVE",
                true, true, 0.1f, 0.2f, true, true, true);
        assertTrue(GameModeValidation.validate(payload).valid());
    }
}
