package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every way a frame can be refused. These are the paths that keep a hostile
 * or corrupt peer from reaching the record binder.
 */
class PluginMessageRejectionTest {

    private final PluginMessageCodec codec = new PluginMessageCodec();

    private byte[] validFrame() {
        return codec.encode(
                new HandoffRequestMessage(UUID.randomUUID(), "survival-1", "context"), UUID.randomUUID());
    }

    private byte[] frameWith(int version, int kind, byte[] payload) {
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES + payload.length];
        System.arraycopy(PluginMessageProtocol.MAGIC, 0, frame, 0, 4);
        frame[4] = (byte) version;
        frame[5] = (byte) kind;
        UUID correlation = UUID.randomUUID();
        long msb = correlation.getMostSignificantBits();
        long lsb = correlation.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            frame[6 + i] = (byte) (msb & 0xff);
            msb >>>= 8;
            frame[14 + i] = (byte) (lsb & 0xff);
            lsb >>>= 8;
        }
        frame[22] = (byte) ((payload.length >>> 24) & 0xff);
        frame[23] = (byte) ((payload.length >>> 16) & 0xff);
        frame[24] = (byte) ((payload.length >>> 8) & 0xff);
        frame[25] = (byte) (payload.length & 0xff);
        System.arraycopy(payload, 0, frame, PluginMessageProtocol.HEADER_BYTES, payload.length);
        return frame;
    }

    @Test
    void oversizeFrameIsRejectedBeforeParsing() {
        byte[] huge = new byte[PluginMessageLimits.DEFAULT_MAX_FRAME_BYTES + 1];
        FrameTooLargeException e = assertThrows(FrameTooLargeException.class,
                () -> codec.decode(huge, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(PluginMessageLimits.DEFAULT_MAX_FRAME_BYTES + 1, e.sizeBytes());
        assertEquals(PluginMessageLimits.DEFAULT_MAX_FRAME_BYTES, e.maxBytes());
    }

    @Test
    void absoluteCapIsEnforcedEvenIfConfiguredHigher() {
        assertThrows(IllegalArgumentException.class,
                () -> new PluginMessageLimits(PluginMessageLimits.ABSOLUTE_MAX_FRAME_BYTES + 1));
    }

    @Test
    void emptyFrameIsRejected() {
        assertThrows(TruncatedFrameException.class,
                () -> codec.decode(new byte[0], PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void frameSmallerThanHeaderIsRejected() {
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES - 1];
        System.arraycopy(PluginMessageProtocol.MAGIC, 0, frame, 0, 4);
        assertThrows(TruncatedFrameException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void frameTruncatedInsideHeaderIsRejected() {
        byte[] frame = validFrame();
        byte[] cut = Arrays.copyOf(frame, 10);
        assertThrows(TruncatedFrameException.class,
                () -> codec.decode(cut, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void payloadTruncatedOnTheWireIsRejected() {
        byte[] frame = validFrame();
        byte[] cut = Arrays.copyOf(frame, frame.length - 3);
        TruncatedFrameException e = assertThrows(TruncatedFrameException.class,
                () -> codec.decode(cut, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(frame.length, e.expectedBytes());
        assertEquals(frame.length - 3, e.actualBytes());
    }

    @Test
    void trailingGarbageAfterPayloadIsRejected() {
        byte[] frame = validFrame();
        byte[] padded = Arrays.copyOf(frame, frame.length + 5);
        Arrays.fill(padded, frame.length, padded.length, (byte) 0x00);
        TrailingBytesException e = assertThrows(TrailingBytesException.class,
                () -> codec.decode(padded, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(5, e.trailingBytes());
    }

    @Test
    void wrongMagicIsRejected() {
        byte[] frame = validFrame();
        frame[0] = 'X';
        InvalidMagicException e = assertThrows(InvalidMagicException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertTrue(e.actualMagic().startsWith("0x"));
    }

    @Test
    void entirelyForeignMagicIsRejected() {
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(),
                "{\"sent_at\":1700000000000}".getBytes(StandardCharsets.UTF_8));
        frame[0] = 'B';
        frame[1] = 'N';
        frame[2] = 'G';
        frame[3] = '!';
        assertThrows(InvalidMagicException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void futureProtocolVersionIsRejected() {
        byte[] frame = frameWith(2, PluginMessageKind.PING.id(),
                "{\"sent_at\":1700000000000}".getBytes(StandardCharsets.UTF_8));
        UnsupportedProtocolVersionException e = assertThrows(UnsupportedProtocolVersionException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(2, e.version());
    }

    @Test
    void zeroProtocolVersionIsRejected() {
        byte[] frame = frameWith(0, PluginMessageKind.PING.id(),
                "{\"sent_at\":1700000000000}".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsupportedProtocolVersionException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void unknownKindByteIsRejected() {
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, 200,
                "{}".getBytes(StandardCharsets.UTF_8));
        UnknownMessageKindException e = assertThrows(UnknownMessageKindException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(200, e.kind());
    }

    @Test
    void kindZeroIsRejected() {
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, 0,
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnknownMessageKindException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void negativeDeclaredLengthIsRejected() {
        byte[] frame = validFrame();
        frame[22] = (byte) 0xFF;
        frame[23] = (byte) 0xFF;
        frame[24] = (byte) 0xFF;
        frame[25] = (byte) 0xFF;
        assertThrows(InvalidPayloadLengthException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void declaredLengthLargerThanFrameIsRejected() {
        byte[] frame = validFrame();
        // claim the payload is 1 KiB longer than it is
        int claimed = frame.length - PluginMessageProtocol.HEADER_BYTES + 1024;
        frame[22] = (byte) ((claimed >>> 24) & 0xff);
        frame[23] = (byte) ((claimed >>> 16) & 0xff);
        frame[24] = (byte) ((claimed >>> 8) & 0xff);
        frame[25] = (byte) (claimed & 0xff);
        TruncatedFrameException e = assertThrows(TruncatedFrameException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(PluginMessageProtocol.HEADER_BYTES + claimed, e.expectedBytes());
    }

    @Test
    void nonUtf8PayloadIsRejectedBeforeJsonParsing() {
        byte[] payload = {(byte) 0xC3, 0x28}; // invalid UTF-8 sequence
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), payload);
        assertThrows(InvalidUtf8Exception.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void loneContinuationByteIsRejected() {
        byte[] payload = {(byte) 0x80, 0x61};
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), payload);
        assertThrows(InvalidUtf8Exception.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void malformedJsonIsRejected() {
        byte[] payload = "{\"player\": ".getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.HANDOFF_REQUEST.id(), payload);
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void jsonArrayRootIsRejected() {
        byte[] payload = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), payload);
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void emptyPayloadIsRejected() {
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), new byte[0]);
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void wrongFieldTypeInPayloadIsRejected() {
        byte[] payload = "{\"sent_at\":\"not a number\"}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), payload);
        assertThrows(MalformedPayloadException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void nilPlayerUuidInPayloadIsRejected() {
        byte[] payload = ("{\"player\":\"00000000-0000-0000-0000-000000000000\","
                + "\"target_server\":\"survival-1\",\"reason\":\"\"}").getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.HANDOFF_REQUEST.id(), payload);
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("player")));
    }

    @Test
    void nilCorrelationIdOnTheWireIsRejected() {
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(),
                "{\"sent_at\":1700000000000}".getBytes(StandardCharsets.UTF_8));
        for (int i = 6; i < 22; i++) {
            frame[i] = 0;
        }
        assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void unknownJsonFieldsAreToleratedForRollingUpgrades() {
        // unknown properties are ignored on purpose (both sides deploy independently);
        // everything else stays strict
        byte[] payload = "{\"sent_at\":1700000000000,\"some_future_field\":{\"nested\":true}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.PING.id(), payload);
        PingMessage decoded = (PingMessage) codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY).message();
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), decoded.sentAt());
    }

    @Test
    void overlongReasonInPayloadIsRejected() {
        byte[] payload = ("{\"player\":\"123e4567-e89b-42d3-a456-426655440000\","
                + "\"target_server\":\"survival-1\",\"reason\":\"" + "r".repeat(257) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.HANDOFF_REQUEST.id(), payload);
        assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void semanticViolationsCarryAllFindings() {
        byte[] payload = ("{\"player\":\"00000000-0000-0000-0000-000000000000\","
                + "\"target_server\":\"NOT A SERVER\",\"reason\":\"" + "r".repeat(300) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(PluginMessageProtocol.CURRENT_VERSION, PluginMessageKind.HANDOFF_REQUEST.id(), payload);
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
        assertEquals(3, e.violations().size(), e.violations().toString());
    }
}
