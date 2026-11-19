package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PluginMessageCodecRoundTripTest {

    private static final Instant EXPIRES = Instant.ofEpochMilli(2_000_000_000_000L);
    private static final Instant SENT_AT = Instant.ofEpochMilli(1_700_000_000_000L);

    private final PluginMessageCodec codec = new PluginMessageCodec();

    static PluginMessage sampleOf(PluginMessageKind kind) {
        UUID player = UUID.randomUUID();
        return switch (kind) {
            case HANDOFF_REQUEST -> new HandoffRequestMessage(player, "survival-1", "switch world");
            case HANDOFF_READY -> new HandoffReadyMessage(player, "survival-1", true, "warmed up");
            case HANDOFF_RESUMED -> new HandoffResumedMessage(player, "survival-1", "restored");
            case LOCALE_SET -> new LocaleSetMessage(player, "en_US");
            case PING -> new PingMessage(SENT_AT);
            case BACKEND_STATUS -> new BackendStatusMessage("survival-1", 17, 100, true, "diamond tier");
            case ROUTE_RESULT -> new RouteResultMessage(player, RouteOutcome.ALLOWED, "survival-1", "ok");
            case SESSION_TOKEN -> new SessionTokenMessage(player, "opaque-token-123", EXPIRES);
            case PONG -> new PongMessage(SENT_AT);
        };
    }

    @Test
    void roundTripEveryKind() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            PluginMessage original = sampleOf(kind);
            UUID correlation = UUID.randomUUID();

            byte[] frame = codec.encode(original, correlation);
            DecodedPluginMessage decoded = codec.decode(frame, kind.direction());

            assertEquals(original, decoded.message(), "payload mismatch for " + kind);
            assertEquals(correlation, decoded.correlationId(), "correlation mismatch for " + kind);
            assertEquals(PluginMessageProtocol.CURRENT_VERSION, decoded.protocolVersion());
        }
    }

    @Test
    void wireLayoutIsFixedAndBigEndian() {
        UUID correlation = UUID.fromString("123e4567-e89b-42d3-a456-426655440000");
        byte[] frame = codec.encode(new HandoffRequestMessage(UUID.randomUUID(), "survival-1", ""), correlation);

        // magic
        assertEquals('c', frame[0] & 0xff);
        assertEquals('t', frame[1] & 0xff);
        assertEquals('m', frame[2] & 0xff);
        assertEquals('m', frame[3] & 0xff);
        // version + kind
        assertEquals(PluginMessageProtocol.CURRENT_VERSION, frame[4] & 0xff);
        assertEquals(PluginMessageKind.HANDOFF_REQUEST.id(), frame[5] & 0xff);
        // correlation UUID as MSB then LSB, big-endian
        long msb = 0, lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (frame[6 + i] & 0xffL);
            lsb = (lsb << 8) | (frame[14 + i] & 0xffL);
        }
        assertEquals(correlation.getMostSignificantBits(), msb);
        assertEquals(correlation.getLeastSignificantBits(), lsb);
        // payload length, big-endian
        int declared = ((frame[22] & 0xff) << 24) | ((frame[23] & 0xff) << 16)
                | ((frame[24] & 0xff) << 8) | (frame[25] & 0xff);
        assertEquals(frame.length - PluginMessageProtocol.HEADER_BYTES, declared);
        assertEquals(PluginMessageProtocol.HEADER_BYTES + declared, frame.length);
    }

    @Test
    void kindIdsAreStableOnTheWire() {
        assertEquals(1, PluginMessageKind.HANDOFF_REQUEST.id());
        assertEquals(2, PluginMessageKind.HANDOFF_READY.id());
        assertEquals(3, PluginMessageKind.HANDOFF_RESUMED.id());
        assertEquals(4, PluginMessageKind.LOCALE_SET.id());
        assertEquals(5, PluginMessageKind.PING.id());
        assertEquals(6, PluginMessageKind.BACKEND_STATUS.id());
        assertEquals(7, PluginMessageKind.ROUTE_RESULT.id());
        assertEquals(8, PluginMessageKind.SESSION_TOKEN.id());
        assertEquals(9, PluginMessageKind.PONG.id());
    }

    @Test
    void payloadJsonUsesSnakeCaseFieldNames() {
        byte[] frame = codec.encode(
                new BackendStatusMessage("survival-1", 3, 100, true, "note"), UUID.randomUUID());
        String json = new String(frame, PluginMessageProtocol.OFFSET_PAYLOAD,
                frame.length - PluginMessageProtocol.OFFSET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"player_count\""), json);
        assertTrue(json.contains("\"max_players\""), json);
        assertTrue(json.contains("\"server\""), json);
        assertTrue(json.contains("\"accepting\""), json);
    }

    @Test
    void routeOutcomeSerializesAsDeclaredName() {
        byte[] frame = codec.encode(
                new RouteResultMessage(UUID.randomUUID(), RouteOutcome.TARGET_UNAVAILABLE, "", "at capacity"),
                UUID.randomUUID());
        String json = new String(frame, PluginMessageProtocol.OFFSET_PAYLOAD,
                frame.length - PluginMessageProtocol.OFFSET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"TARGET_UNAVAILABLE\""), json);
    }

    @Test
    void expiresAtRoundTripsAsEpochMilli() {
        byte[] frame = codec.encode(
                new SessionTokenMessage(UUID.randomUUID(), "tok", EXPIRES), UUID.randomUUID());
        String json = new String(frame, PluginMessageProtocol.OFFSET_PAYLOAD,
                frame.length - PluginMessageProtocol.OFFSET_PAYLOAD, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"expires_at\":" + EXPIRES.toEpochMilli()), json);
    }

    @Test
    void encodeRejectsNilCorrelationId() {
        assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(new PingMessage(SENT_AT), new UUID(0, 0)));
    }

    @Test
    void encodeRejectsInvalidMessageBeforeFraming() {
        // illegal server name must never reach the wire
        HandoffRequestMessage broken = new HandoffRequestMessage(UUID.randomUUID(), "NOT A SERVER", "");
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("targetServer")));
    }

    @Test
    void encodeReportsAllViolationsAtOnce() {
        HandoffRequestMessage broken = new HandoffRequestMessage(new UUID(0, 0), "!!", "x".repeat(300));
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
        assertEquals(3, e.violations().size(), e.violations().toString());
    }

    @Test
    void encodeExceedingCapIsRejected() {
        PluginMessageCodec tight = new PluginMessageCodec(new PluginMessageLimits(64));
        // ping needs ~48 bytes total, but a big reason cannot fit into 64 - it must fail cleanly
        HandoffRequestMessage tooBig = new HandoffRequestMessage(UUID.randomUUID(), "survival-1", "r".repeat(256));
        assertThrows(FrameTooLargeException.class, () -> tight.encode(tooBig, UUID.randomUUID()));
    }

    @Test
    void frameExactlyAtCapIsAccepted() {
        HandoffRequestMessage big = new HandoffRequestMessage(UUID.randomUUID(), "survival-1", "r".repeat(256));
        byte[] reference = codec.encode(big, UUID.randomUUID());
        PluginMessageLimits exact = new PluginMessageLimits(reference.length);
        PluginMessageCodec tight = new PluginMessageCodec(exact);
        byte[] frame = tight.encode(big, UUID.randomUUID());
        assertEquals(reference.length, frame.length);
        assertDoesNotThrow(() -> tight.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void frameOneByteOverCapIsRejectedOnDecode() {
        HandoffRequestMessage big = new HandoffRequestMessage(UUID.randomUUID(), "survival-1", "r".repeat(256));
        byte[] reference = codec.encode(big, UUID.randomUUID());
        PluginMessageLimits oneShort = new PluginMessageLimits(reference.length - 1);
        PluginMessageCodec tight = new PluginMessageCodec(oneShort);
        FrameTooLargeException e = assertThrows(FrameTooLargeException.class,
                () -> tight.decode(reference, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(reference.length, e.sizeBytes());
        assertEquals(reference.length - 1, e.maxBytes());
    }

    @Test
    void decodeRejectsCrossedDirection() {
        byte[] requestFrame = codec.encode(
                new HandoffRequestMessage(UUID.randomUUID(), "survival-1", ""), UUID.randomUUID());
        assertThrows(DirectionMismatchException.class,
                () -> codec.decode(requestFrame, PluginMessageDirection.PROXY_TO_BACKEND));

        byte[] replyFrame = codec.encode(
                new RouteResultMessage(UUID.randomUUID(), RouteOutcome.DENIED, "", "no"), UUID.randomUUID());
        assertThrows(DirectionMismatchException.class,
                () -> codec.decode(replyFrame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void encodeAndDecodeRoundTripsLargeReason() {
        HandoffRequestMessage message = new HandoffRequestMessage(UUID.randomUUID(), "survival-1", "r".repeat(256));
        byte[] frame = codec.encode(message, UUID.randomUUID());
        DecodedPluginMessage decoded = codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY);
        assertEquals(message, decoded.message());
    }
}
