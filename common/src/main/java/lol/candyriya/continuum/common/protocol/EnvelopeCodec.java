package lol.candyriya.continuum.common.protocol;

import lol.candyriya.continuum.common.internal.JacksonMappers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Envelope codec for the wire. Same conventions as snapshots: snake_case
 * keys, instants as epoch-milli, {@code byte[]} as Base64 — no Java native
 * serialization, ever.
 *
 * <p>Decoding is strict by construction: the byte budget is checked before a
 * single parse happens, the payload class is resolved through the
 * {@link MessageTypeRegistry} (never from the frame's own type name), and the
 * reassembled envelope is structurally validated before it is handed out.</p>
 */
public final class EnvelopeCodec {

    private static final tools.jackson.core.type.TypeReference<MessageEnvelope<JsonNode>> ENVELOPE_TYPE
            = new tools.jackson.core.type.TypeReference<>() {
    };

    private final MessageTypeRegistry registry;
    private final int maxEnvelopeBytes;
    private final JsonMapper mapper;

    public EnvelopeCodec(MessageTypeRegistry registry, int maxEnvelopeBytes) {
        if (registry == null) {
            throw new NullPointerException("registry must not be null");
        }
        if (maxEnvelopeBytes < 1_024) {
            throw new IllegalArgumentException("maxEnvelopeBytes must be >= 1024, got " + maxEnvelopeBytes);
        }
        this.registry = registry;
        this.maxEnvelopeBytes = maxEnvelopeBytes;
        this.mapper = JacksonMappers.create();
    }

    /** Encodes {@code envelope}, enforcing the size cap on the final frame. */
    public <P extends EventPayload> byte[] encode(MessageEnvelope<P> envelope) {
        EnvelopeValidator.requireValid(envelope);
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new ProtocolException("failed to encode envelope for event type '" + envelope.eventType() + "'", e);
        }
        String violation = EnvelopeValidator.checkSize(bytes.length, maxEnvelopeBytes);
        if (violation != null) {
            throw new ProtocolException("refusing to encode " + violation);
        }
        return bytes;
    }

    /**
     * Decodes {@code bytes} and casts the payload to {@code payloadType}.
     * Strict: the event type must be registered, and the registry's payload
     * class must be assignable to {@code payloadType}.
     */
    public <P extends EventPayload> MessageEnvelope<P> decode(byte[] bytes, Class<P> payloadType) {
        MessageEnvelope<JsonNode> raw = decodeUntyped(bytes);
        Class<? extends EventPayload> registered = registry.resolve(raw.eventType())
                .orElseThrow(() -> new ProtocolException("unknown event type '" + raw.eventType() + "'"));
        if (!payloadType.isAssignableFrom(registered)) {
            throw new ProtocolException("event type '" + raw.eventType() + "' is registered to "
                    + registered.getSimpleName() + ", cannot decode as " + payloadType.getSimpleName());
        }
        P payload = convertPayload(raw.payload(), payloadType, raw.eventType());
        return new MessageEnvelope<>(raw.protocolVersion(), raw.eventType(), raw.eventId(),
                raw.timestamp(), raw.correlationId(), payload);
    }

    /**
     * Decodes {@code bytes} without binding the payload. Intended for relays,
     * inspectors and dead-letter tooling — application code should use the
     * typed overload.
     */
    public MessageEnvelope<JsonNode> decodeUntyped(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException("frame is empty");
        }
        String violation = EnvelopeValidator.checkSize(bytes.length, maxEnvelopeBytes);
        if (violation != null) {
            throw new ProtocolException("refusing to decode " + violation);
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(bytes);
        } catch (Exception e) {
            throw new ProtocolException("frame is not valid JSON", e);
        }
        if (tree == null || !tree.isObject()) {
            throw new ProtocolException("frame must be a JSON object");
        }
        MessageEnvelope<JsonNode> envelope = toEnvelope(tree);
        EnvelopeValidator.requireValid(envelope);
        return envelope;
    }

    /** Resolves the payload class for a registered event type. */
    public Optional<Class<? extends EventPayload>> resolvePayloadType(String eventType) {
        return registry.resolve(eventType);
    }

    private MessageEnvelope<JsonNode> toEnvelope(JsonNode tree) {
        try {
            return mapper.treeToValue(tree, ENVELOPE_TYPE);
        } catch (Exception e) {
            throw new ProtocolException("frame does not match the envelope shape", e);
        }
    }

    private <P extends EventPayload> P convertPayload(JsonNode payloadNode, Class<P> payloadType, String eventType) {
        if (payloadNode == null) {
            throw new ProtocolException("frame for event type '" + eventType + "' carries no payload");
        }
        try {
            P payload = mapper.treeToValue(payloadNode, payloadType);
            if (payload == null) {
                throw new ProtocolException("frame payload for event type '" + eventType + "' decoded to null");
            }
            return payload;
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException(
                    "frame payload for event type '" + eventType + "' does not match " + payloadType.getSimpleName(), e);
        }
    }
}
