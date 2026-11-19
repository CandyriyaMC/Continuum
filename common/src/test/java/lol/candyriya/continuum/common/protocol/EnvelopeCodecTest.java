package lol.candyriya.continuum.common.protocol;

import lol.candyriya.continuum.common.protocol.payload.CoreEventTypes;
import lol.candyriya.continuum.common.protocol.payload.TransferCommittedPayload;
import lol.candyriya.continuum.common.protocol.payload.TransferRequestedPayload;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeCodecTest {

    private static final int MAX_BYTES = 4 * 1024;

    private final MessageTypeRegistry registry = CoreEventTypes.create();
    private final EnvelopeCodec codec = new EnvelopeCodec(registry, MAX_BYTES);

    private static MessageEnvelope<TransferRequestedPayload> sampleEnvelope() {
        return new MessageEnvelope<>(
                Protocol.CURRENT_VERSION,
                ProtocolEvents.TRANSFER_REQUESTED,
                UUID.randomUUID(),
                Instant.ofEpochMilli(1_700_000_000_000L),
                "correlation-" + UUID.randomUUID(),
                new TransferRequestedPayload(UUID.randomUUID(), "survival-1", "lobby-1", 41));
    }

    @Test
    void encodeDecodeRoundTripPreservesEverything() {
        MessageEnvelope<TransferRequestedPayload> original = sampleEnvelope();
        byte[] frame = codec.encode(original);
        MessageEnvelope<TransferRequestedPayload> decoded = codec.decode(frame, TransferRequestedPayload.class);

        assertEquals(original.protocolVersion(), decoded.protocolVersion());
        assertEquals(original.eventType(), decoded.eventType());
        assertEquals(original.eventId(), decoded.eventId());
        assertEquals(original.timestamp(), decoded.timestamp());
        assertEquals(original.correlationId(), decoded.correlationId());
        assertEquals(original.payload(), decoded.payload());
    }

    @Test
    void frameIsSnakeCaseJson() {
        String json = new String(codec.encode(sampleEnvelope()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"protocol_version\""), json);
        assertTrue(json.contains("\"event_type\""), json);
        assertTrue(json.contains("\"event_id\""), json);
        assertTrue(json.contains("\"correlation_id\""), json);
        assertTrue(json.contains("\"from_server_id\""), json);
        assertTrue(json.contains("\"to_server_id\""), json);
        assertTrue(json.contains("\"fencing_token\""), json);
    }

    @Test
    void unknownEventTypeIsRejected() {
        MessageEnvelope<JsonNode> raw = codec.decodeUntyped(newEnvelopeBytes());
        assertEquals("continuum.transfer.requested", raw.eventType());

        // craft a frame with an unregistered type straight from a tree
        var mapper = lol.candyriya.continuum.common.internal.JacksonMappers.create();
        JsonNode tree;
        try {
            tree = mapper.readTree("""
                    {"protocol_version":1,"event_type":"continuum.unknown.event","event_id":"%s","timestamp":1700000000000,"correlation_id":"c","payload":{}}
                    """.formatted(UUID.randomUUID()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        byte[] frame = mapper.writeValueAsBytes(tree);
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> codec.decode(frame, TransferRequestedPayload.class));
        assertTrue(e.getMessage().contains("continuum.unknown.event"));
    }

    @Test
    void payloadTypeMismatchIsRejected() {
        byte[] frame = codec.encode(sampleEnvelope());
        // TRANSFER_REQUESTED is registered to TransferRequestedPayload, not to TransferCommittedPayload
        assertThrows(ProtocolException.class,
                () -> codec.decode(frame, TransferCommittedPayload.class));
    }

    @Test
    void oversizeFrameIsRejectedBeforeParsing() {
        byte[] huge = new byte[MAX_BYTES + 1];
        ProtocolException e = assertThrows(ProtocolException.class, () -> codec.decode(huge, TransferRequestedPayload.class));
        assertTrue(e.getMessage().contains("limit"));
        assertEquals("protocol.violation", e.code());
    }

    @Test
    void encodeRefusesWhenSerializedFrameExceedsCap() {
        var tight = new EnvelopeCodec(registry, 1_024);
        var bigCorrelation = new MessageEnvelope<>(
                Protocol.CURRENT_VERSION,
                ProtocolEvents.TRANSFER_REQUESTED,
                UUID.randomUUID(),
                Instant.now(),
                "c".repeat(128),
                new TransferRequestedPayload(UUID.randomUUID(), "a".repeat(400), "b".repeat(400), 1));
        assertThrows(ProtocolException.class, () -> tight.encode(bigCorrelation));
    }

    @Test
    void structuralValidationRejectsBadVersion() {
        var envelope = new MessageEnvelope<>(
                99, ProtocolEvents.TRANSFER_REQUESTED, UUID.randomUUID(), Instant.now(), "c",
                new TransferRequestedPayload(UUID.randomUUID(), "a", "b", 1));
        assertThrows(ProtocolException.class, () -> codec.encode(envelope));
    }

    @Test
    void structuralValidationRejectsBlankCorrelationId() {
        assertThrows(IllegalArgumentException.class, () -> new MessageEnvelope<>(
                1, ProtocolEvents.TRANSFER_REQUESTED, UUID.randomUUID(), Instant.now(), "   ",
                new TransferRequestedPayload(UUID.randomUUID(), "a", "b", 1)));
    }

    @Test
    void decodeUntypedKeepsPayloadRaw() {
        byte[] frame = codec.encode(sampleEnvelope());
        MessageEnvelope<JsonNode> raw = codec.decodeUntyped(frame);
        assertEquals(ProtocolEvents.TRANSFER_REQUESTED, raw.eventType());
        assertEquals(41, raw.payload().get("fencing_token").asLong());
    }

    @Test
    void tamperedJsonIsRejected() {
        assertThrows(ProtocolException.class,
                () -> codec.decode("{not json".getBytes(), TransferRequestedPayload.class));
    }

    @Test
    void emptyFrameIsRejected() {
        assertThrows(ProtocolException.class, () -> codec.decode(new byte[0], TransferRequestedPayload.class));
    }

    @Test
    void registryRejectsBadEventTypeSyntax() {
        var registry = new MessageTypeRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("NoDots", TransferRequestedPayload.class));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("UPPER.case.name", TransferRequestedPayload.class));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("has space.name", TransferRequestedPayload.class));
    }

    @Test
    void registryRejectsDuplicateRegistration() {
        var registry = new MessageTypeRegistry();
        registry.register("continuum.transfer.requested", TransferRequestedPayload.class);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("continuum.transfer.requested", TransferRequestedPayload.class));
    }

    private byte[] newEnvelopeBytes() {
        return codec.encode(sampleEnvelope());
    }
}

