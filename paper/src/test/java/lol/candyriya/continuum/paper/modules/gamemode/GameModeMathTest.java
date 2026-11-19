package lol.candyriya.continuum.paper.modules.gamemode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameModeMathTest {

    @Test
    void speedClampsToApiRange() {
        assertEquals(-1.0f, GameModeMath.resolveSpeed(-1.0f));
        assertEquals(-1.0f, GameModeMath.resolveSpeed(-2.5f));
        assertEquals(0.0f, GameModeMath.resolveSpeed(0.0f));
        assertEquals(0.1f, GameModeMath.resolveSpeed(0.1f));
        assertEquals(0.2f, GameModeMath.resolveSpeed(0.2f));
        assertEquals(1.0f, GameModeMath.resolveSpeed(1.0f));
        assertEquals(1.0f, GameModeMath.resolveSpeed(3.7f));
    }

    @Test
    void negativeValuesArePreserved() {
        // vanilla: negative speed = reverse direction — the bound is the API range, not 0..1
        assertEquals(-0.5f, GameModeMath.resolveSpeed(-0.5f));
    }

    @Test
    void nonFiniteClampsToBoundsNanCollapsesToZero() {
        assertEquals(0.0f, GameModeMath.resolveSpeed(Float.NaN));
        assertEquals(1.0f, GameModeMath.resolveSpeed(Float.POSITIVE_INFINITY));
        assertEquals(-1.0f, GameModeMath.resolveSpeed(Float.NEGATIVE_INFINITY));
    }
}
