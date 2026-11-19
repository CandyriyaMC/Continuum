package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.messaging.outbox.OutboxEvent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-to-relay mapping rules: stable handles, honest bytes, safe envelopes.
 */
class OutboxClaimEventMappingTest {

    @Test
    void eventIdsAreStableForTheSameRowAndDistinctAcrossRows() {
        UUID first = OutboxClaimSupport.eventIdFor(42L);
        UUID second = OutboxClaimSupport.eventIdFor(42L);
        UUID other = OutboxClaimSupport.eventIdFor(43L);
        assertEquals(first, second, "the same row must always map to the same relay handle");
        assertNotEquals(first, other);
    }

    @Test
    void toEventCarriesTheRowThroughVerbatim() {
        Instant created = Instant.parse("2026-11-19T12:00:00Z");
        OutboxEvent event = OutboxClaimSupport.toEvent(7L, "continuum.recovery.required",
                "11111111-1111-1111-1111-111111111111", "{\"hello\":\"world\"}", 3, created);

        assertEquals(3, event.attempts(), "attempts must survive the mapping");
        assertEquals("continuum.recovery.required", event.eventType());
        assertEquals("11111111-1111-1111-1111-111111111111", event.correlationId());
        assertEquals(created, event.createdAt());
        assertArrayEquals("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8), event.envelope());
        assertEquals(OutboxClaimSupport.eventIdFor(7L), event.eventId());
    }

    @Test
    void blankPayloadsBecomeAnObjectSoTheRelayCanRetireThemCleanly() {
        OutboxEvent event = OutboxClaimSupport.toEvent(9L, "test.blank",
                "11111111-1111-1111-1111-111111111111", "", 0, Instant.now());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), event.envelope());
    }

    @Test
    void nullPayloadsBecomeAnObjectToo() {
        OutboxEvent event = OutboxClaimSupport.toEvent(10L, "test.null",
                "11111111-1111-1111-1111-111111111111", null, 0, Instant.now());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), event.envelope());
    }

    @Test
    void truncationCutsAt512AndReplacesBlanks() {
        String longError = "x".repeat(700);
        assertEquals(512, OutboxClaimSupport.truncate(longError).length());
        assertTrue(OutboxClaimSupport.truncate(longError).chars().allMatch(c -> c == 'x'));

        assertEquals("unknown publisher error", OutboxClaimSupport.truncate(null));
        assertEquals("unknown publisher error", OutboxClaimSupport.truncate("   "));
        assertEquals("short", OutboxClaimSupport.truncate("short"));
    }
}
