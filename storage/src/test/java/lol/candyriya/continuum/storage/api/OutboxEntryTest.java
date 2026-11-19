package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxEntryTest {

    private static OutboxEntry entry(long id, String msgType, int attempts, String lastError, Instant publishedAt) {
        return new OutboxEntry(id, msgType, UUID.randomUUID(), "{\"a\":1}", Instant.now(), publishedAt, attempts, lastError);
    }

    @Test
    void validEntryRoundTrips() {
        OutboxEntry entry = entry(42, "session.handoff", 0, null, null);
        assertEquals(42, entry.id());
        assertEquals("session.handoff", entry.msgType());
        assertFalse(entry.published());
    }

    @Test
    void publishedIsTrueOnlyWithPublishedAt() {
        assertFalse(entry(1, "t", 0, null, null).published());
        assertTrue(entry(1, "t", 0, null, Instant.now()).published());
    }

    @Test
    void idMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> entry(0, "t", 0, null, null));
    }

    @Test
    void msgTypeMustBeNonBlankAndBounded() {
        assertThrows(IllegalArgumentException.class, () -> entry(1, "   ", 0, null, null));
        assertThrows(NullPointerException.class, () -> entry(1, null, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> entry(1, "x".repeat(65), 0, null, null));
        entry(1, "x".repeat(64), 0, null, null); // boundary is fine
    }

    @Test
    void attemptsMustNotBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> entry(1, "t", -1, null, null));
    }

    @Test
    void lastErrorIsTruncatedTo512() {
        OutboxEntry entry = entry(1, "t", 1, "e".repeat(1000), null);
        assertEquals(512, entry.lastError().length());
    }

    @Test
    void payloadMustNotBeNull() {
        assertThrows(NullPointerException.class,
                () -> new OutboxEntry(1, "t", UUID.randomUUID(), null, Instant.now(), null, 0, null));
    }
}
