package lol.candyriya.continuum.velocity.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTokenSourceTest {

    @Test
    void inMemoryTokensAreStrictlyIncreasingFromOne() {
        var source = new InMemorySessionTokenSource();
        assertEquals(1, source.nextToken());
        assertEquals(2, source.nextToken());
        assertEquals(3, source.nextToken());
    }

    @Test
    void inMemoryTokensHonorCustomSeed() {
        var source = new InMemorySessionTokenSource(1_000_000);
        assertEquals(1_000_000, source.nextToken());
    }

    @Test
    void inMemoryRejectsNonsenseSeed() {
        assertThrows(IllegalArgumentException.class, () -> new InMemorySessionTokenSource(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemorySessionTokenSource(-5));
    }

    @Test
    void inMemoryFailsLoudlyWhenSpaceIsExhausted() {
        var source = new InMemorySessionTokenSource(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, source.nextToken());
        assertThrows(SessionTokenException.class, source::nextToken);
    }

    @Test
    void unavailableSourceFailsWithActionableMessage() {
        var source = new UnavailableSessionTokenSource();
        var exception = assertThrows(SessionTokenException.class, source::nextToken);
        assertTrue(exception.getMessage().contains("not wired"));
    }
}
