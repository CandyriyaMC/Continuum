package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.protocol.MessageEnvelope;

import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * One delivery handed to a {@link MessageHandler}: the raw-ish envelope plus
 * the broker's delivery context.
 *
 * <p>The payload is intentionally <b>not</b> bound to a concrete record here.
 * Consumers decode with {@code EnvelopeCodec.decode(bytes, MyPayload.class)}
 * or read the JSON node directly; relays and dead-letter tooling work with
 * the node as-is. Binding at this layer would force every subscriber to know
 * every payload type, which is exactly the coupling we do not want.</p>
 */
public record InboundMessage(DeliveryMetadata metadata, MessageEnvelope<JsonNode> envelope) {

    public InboundMessage {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(envelope, "envelope must not be null");
    }
}
