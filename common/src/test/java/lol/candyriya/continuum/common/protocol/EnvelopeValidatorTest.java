package lol.candyriya.continuum.common.protocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeValidatorTest {

    private static MessageEnvelope<EventPayload> envelope(int version, String eventType, Instant timestamp, String correlationId) {
        return new MessageEnvelope<>(version, eventType, UUID.randomUUID(), timestamp, correlationId, new EventPayload() {
        });
    }

    @Test
    void validEnvelopeHasNoViolations() {
        var envelope = envelope(Protocol.CURRENT_VERSION, "continuum.transfer.requested", Instant.now(), "corr-1");
        assertTrue(EnvelopeValidator.validate(envelope).isEmpty());
        assertDoesNotThrow(() -> EnvelopeValidator.requireValid(envelope));
    }

    @Test
    void unsupportedVersionsAreFlagged() {
        assertTrue(EnvelopeValidator.validate(envelope(0, "continuum.transfer.requested", Instant.now(), "c"))
                .stream().anyMatch(v -> v.startsWith("protocol_version")));
        assertTrue(EnvelopeValidator.validate(envelope(2, "continuum.transfer.requested", Instant.now(), "c"))
                .stream().anyMatch(v -> v.startsWith("protocol_version")));
        assertThrows(ProtocolException.class,
                () -> EnvelopeValidator.requireValid(envelope(2, "continuum.transfer.requested", Instant.now(), "c")));
    }

    @Test
    void malformedEventTypesAreFlagged() {
        // (blank eventType never reaches validation — the compact constructor rejects it)
        for (String bad : new String[]{"UPPER.CASE.x", "no-dot", ".leading.dot", "double..dot", "-leadingdash.x", "has space.x"}) {
            assertTrue(EnvelopeValidator.validate(envelope(1, bad, Instant.now(), "c"))
                    .stream().anyMatch(v -> v.startsWith("event_type")), "should flag: '" + bad + "'");
        }
    }

    @Test
    void validEventTypesPass() {
        for (String good : new String[]{"continuum.transfer.requested", "a.b", "x1.y2", "continuum.x-y.z_w"}) {
            assertTrue(EnvelopeValidator.validate(envelope(1, good, Instant.now(), "c"))
                    .stream().noneMatch(v -> v.startsWith("event_type")), "should pass: '" + good + "'");
        }
    }

    @Test
    void farFutureTimestampIsFlagged() {
        Instant tooFar = Instant.now().plus(Duration.ofMinutes(10));
        assertTrue(EnvelopeValidator.validate(envelope(1, "continuum.transfer.requested", tooFar, "c"))
                .stream().anyMatch(v -> v.startsWith("timestamp")));
    }

    @Test
    void reasonableSkewIsTolerated() {
        Instant withinSkew = Instant.now().plus(Duration.ofMinutes(2));
        assertFalse(EnvelopeValidator.validate(envelope(1, "continuum.transfer.requested", withinSkew, "c"))
                .stream().anyMatch(v -> v.startsWith("timestamp")));
    }

    @Test
    void oversizedCorrelationIdIsFlagged() {
        String longCorrelation = "c".repeat(EnvelopeValidator.MAX_CORRELATION_ID_LENGTH + 1);
        assertTrue(EnvelopeValidator.validate(envelope(1, "continuum.transfer.requested", Instant.now(), longCorrelation))
                .stream().anyMatch(v -> v.startsWith("correlation_id")));
    }

    @Test
    void checkSizeReportsViolationOnlyWhenExceeded() {
        assertNull(EnvelopeValidator.checkSize(100, 100));
        assertNotNull(EnvelopeValidator.checkSize(101, 100));
        assertTrue(EnvelopeValidator.checkSize(101, 100).contains("101"));
    }

    @Test
    void createFillsCurrentVersionAndFreshEventId() {
        var original = envelope(1, "continuum.transfer.requested", Instant.now(), "c");
        var created = MessageEnvelope.create("continuum.transfer.requested", "corr", original.payload());
        assertEquals(Protocol.CURRENT_VERSION, created.protocolVersion());
        assertNotNull(created.eventId());
        assertEquals("corr", created.correlationId());
        assertTrue(EnvelopeValidator.validate(created).isEmpty());
    }
}
