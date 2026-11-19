package lol.candyriya.continuum.common.protocol;

import lol.candyriya.continuum.common.util.Preconditions;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The single envelope for every Continuum message, whichever transport carries it.
 *
 * <p>{@code P} is unbounded so relays can hold a raw JSON payload; typed use
 * sites bind it to {@link EventPayload}.</p>
 *
 * @param protocolVersion wire version, see {@link Protocol}
 * @param eventType       dotted lowercase event id, e.g. {@code continuum.transfer.requested}
 * @param eventId         unique per emission; consumers dedupe on it (at-least-once delivery)
 * @param timestamp       emission time, instant (epoch-milli on the wire)
 * @param correlationId   links causally related messages across a transfer;
 *                        platforms generate one when the flow starts
 * @param payload         event body (typed record, or raw JSON for relays)
 */
public record MessageEnvelope<P>(int protocolVersion,
                                 String eventType,
                                 UUID eventId,
                                 Instant timestamp,
                                 String correlationId,
                                 P payload) {

    public MessageEnvelope {
        eventType = Preconditions.notBlank(eventType, "eventType");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        correlationId = Preconditions.notBlank(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /** Fresh envelope for {@code eventType} with current protocol version and a new event id. */
    public static <P extends EventPayload> MessageEnvelope<P> create(String eventType, String correlationId, P payload) {
        return new MessageEnvelope<>(Protocol.CURRENT_VERSION, eventType, UUID.randomUUID(), Instant.now(), correlationId, payload);
    }
}
