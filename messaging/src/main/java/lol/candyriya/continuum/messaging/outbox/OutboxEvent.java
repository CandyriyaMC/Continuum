package lol.candyriya.continuum.messaging.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pending event as the relay sees it. {@code envelope} is the exact
 * serialized frame that was written into the outbox at write time, inside the
 * caller's database transaction. Re-publishing the same row later carries the
 * same {@code eventId}, which is what idempotent consumers dedupe on.
 */
public record OutboxEvent(UUID eventId,
                          String eventType,
                          String correlationId,
                          byte[] envelope,
                          int attempts,
                          Instant createdAt) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (envelope.length == 0) {
            throw new IllegalArgumentException("envelope must not be empty");
        }
        envelope = envelope.clone();
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0, got " + attempts);
        }
    }

    /** Defensive copy — the record would otherwise hand out the mutable array. */
    @Override
    public byte[] envelope() {
        return envelope.clone();
    }

    @Override
    public String toString() {
        return "OutboxEvent[eventId=" + eventId + ", eventType=" + eventType
                + ", correlationId=" + correlationId + ", envelopeBytes=" + envelope.length
                + ", attempts=" + attempts + ", createdAt=" + createdAt + "]";
    }
}
