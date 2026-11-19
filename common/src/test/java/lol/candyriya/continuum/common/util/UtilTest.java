package lol.candyriya.continuum.common.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void uuidTryParseAcceptsValidUuids() {
        UUID id = UUID.randomUUID();
        assertEquals(id, UuidUtils.tryParse(id.toString()).orElseThrow());
        assertEquals(id, UuidUtils.tryParse("  " + id + "  ").orElseThrow(), "trimmed input parses");
    }

    @Test
    void uuidTryParseRejectsGarbage() {
        assertTrue(UuidUtils.tryParse("not-a-uuid").isEmpty());
        assertTrue(UuidUtils.tryParse("").isEmpty());
        assertTrue(UuidUtils.tryParse(null).isEmpty());
        assertTrue(UuidUtils.tryParse("3fa85f64-5717-4562-b3fc").isEmpty(), "short uuid rejected");
    }

    @Test
    void timesFormatRendersMillisUnderOneSecond() {
        assertEquals("400ms", Times.format(Duration.ofMillis(400)));
        assertEquals("0ms", Times.format(Duration.ZERO));
    }

    @Test
    void timesFormatRendersSecondsAndMinutes() {
        assertEquals("1m 30s", Times.format(Duration.ofSeconds(90)));
        assertEquals("5m 3s", Times.format(Duration.ofSeconds(303)));
        assertEquals("5m", Times.format(Duration.ofMinutes(5)));
    }

    @Test
    void timesFormatRendersHoursWithoutSeconds() {
        assertEquals("2h", Times.format(Duration.ofHours(2)));
        assertEquals("1h 30m", Times.format(Duration.ofMinutes(90)));
    }

    @Test
    void timesFormatRejectsNull() {
        assertThrows(NullPointerException.class, () -> Times.format(null));
    }

    @Test
    void preconditionsNotBlankRejectsBlankAndNull() {
        assertThrows(NullPointerException.class, () -> Preconditions.notBlank(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> Preconditions.notBlank("   ", "x"));
        assertEquals("ok", Preconditions.notBlank("ok", "x"));
    }

    @Test
    void preconditionsCheckArgumentAndState() {
        assertThrows(IllegalArgumentException.class, () -> Preconditions.checkArgument(false, "arg broke"));
        assertThrows(IllegalStateException.class, () -> Preconditions.checkState(false, "state broke"));
        assertDoesNotThrow(() -> {
            Preconditions.checkArgument(true, "unused");
            Preconditions.checkState(true, "unused");
        });
    }
}
