package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two capture-seam kinds: byte 10 (proxy asks to capture) and byte 11
 * (backend reports the outcome). These frames exist so the proxy can ask for
 * a pre-switch snapshot before a vanilla server switch sends the player off.
 */
class CaptureMessagesTest {

    private final PluginMessageCodec codec = new PluginMessageCodec();

    @Test
    void captureRequestRoundTrips() {
        CaptureRequestMessage original = new CaptureRequestMessage(UUID.randomUUID(), "transfer");
        UUID correlation = UUID.randomUUID();

        byte[] frame = codec.encode(original, correlation);
        DecodedPluginMessage decoded = codec.decode(frame, PluginMessageDirection.PROXY_TO_BACKEND);

        assertEquals(original, decoded.message());
        assertEquals(correlation, decoded.correlationId());
        assertEquals(10, frame[PluginMessageProtocol.OFFSET_KIND] & 0xff);
    }

    @Test
    void captureResultRoundTripsWithNulls() {
        // the "nothing was persisted" shape: no id, no detail, captured=false
        CaptureResultMessage original = new CaptureResultMessage(false, null, null);
        UUID correlation = UUID.randomUUID();

        byte[] frame = codec.encode(original, correlation);
        DecodedPluginMessage decoded = codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY);

        assertEquals(original, decoded.message());
        assertEquals(correlation, decoded.correlationId());
        assertEquals(11, frame[PluginMessageProtocol.OFFSET_KIND] & 0xff);
    }

    @Test
    void captureResultRoundTripsPersisted() {
        CaptureResultMessage original = new CaptureResultMessage(true, 987654321L, null);
        UUID correlation = UUID.randomUUID();

        byte[] frame = codec.encode(original, correlation);
        DecodedPluginMessage decoded = codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY);

        assertEquals(original, decoded.message());
        assertEquals(decoded.message().getClass(), CaptureResultMessage.class);
        assertEquals(987654321L, ((CaptureResultMessage) decoded.message()).snapshotId());
    }

    @Test
    void captureResultCarriesFailureDetail() {
        CaptureResultMessage original = new CaptureResultMessage(false, null, "a required module failed");
        byte[] frame = codec.encode(original, UUID.randomUUID());
        DecodedPluginMessage decoded = codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY);
        assertEquals(original, decoded.message());
    }

    @Test
    void payloadUsesSnakeCaseFieldNames() {
        byte[] request = codec.encode(new CaptureRequestMessage(UUID.randomUUID(), "switch"), UUID.randomUUID());
        String requestJson = new String(request, PluginMessageProtocol.OFFSET_PAYLOAD,
                request.length - PluginMessageProtocol.OFFSET_PAYLOAD, StandardCharsets.UTF_8);
        assertTrue(requestJson.contains("\"target_uuid\""), requestJson);
        assertTrue(requestJson.contains("\"reason\""), requestJson);

        byte[] result = codec.encode(new CaptureResultMessage(true, 7L, null), UUID.randomUUID());
        String resultJson = new String(result, PluginMessageProtocol.OFFSET_PAYLOAD,
                result.length - PluginMessageProtocol.OFFSET_PAYLOAD, StandardCharsets.UTF_8);
        assertTrue(resultJson.contains("\"captured\""), resultJson);
        assertTrue(resultJson.contains("\"snapshot_id\""), resultJson);
        assertTrue(resultJson.contains("\"detail\""), resultJson);
    }

    @Test
    void captureRequestRejectsNilTargetUuidOnEncode() {
        CaptureRequestMessage broken = new CaptureRequestMessage(new UUID(0, 0), "switch");
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("targetUuid")));
    }

    @Test
    void captureRequestRejectsOverlongReasonOnEncode() {
        CaptureRequestMessage broken = new CaptureRequestMessage(UUID.randomUUID(), "r".repeat(257));
        assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
    }

    @Test
    void captureResultRejectsZeroSnapshotIdOnEncode() {
        CaptureResultMessage broken = new CaptureResultMessage(true, 0L, null);
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("snapshotId")));
    }

    @Test
    void captureResultRejectsControlCharsInDetailOnEncode() {
        CaptureResultMessage broken = new CaptureResultMessage(false, null, "boom\u0007bell");
        PluginMessageValidationException e = assertThrows(PluginMessageValidationException.class,
                () -> codec.encode(broken, UUID.randomUUID()));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("detail")));
    }

    @Test
    void nilTargetUuidInPayloadIsRejectedOnDecode() {
        byte[] payload = ("{\"target_uuid\":\"00000000-0000-0000-0000-000000000000\",\"reason\":\"switch\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(10, payload);
        assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.PROXY_TO_BACKEND));
    }

    @Test
    void negativeSnapshotIdInPayloadIsRejectedOnDecode() {
        byte[] payload = "{\"captured\":true,\"snapshot_id\":-1,\"detail\":null}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] frame = frameWith(11, payload);
        assertThrows(PluginMessageValidationException.class,
                () -> codec.decode(frame, PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void captureKindsAreNotCrossDecodable() {
        byte[] request = codec.encode(new CaptureRequestMessage(UUID.randomUUID(), "switch"), UUID.randomUUID());
        assertThrows(DirectionMismatchException.class,
                () -> codec.decode(request, PluginMessageDirection.BACKEND_TO_PROXY));

        byte[] result = codec.encode(new CaptureResultMessage(true, 1L, null), UUID.randomUUID());
        assertThrows(DirectionMismatchException.class,
                () -> codec.decode(result, PluginMessageDirection.PROXY_TO_BACKEND));
    }

    @Test
    void deadBytesAreStillRejectedOnDecode() {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        assertThrows(UnknownMessageKindException.class,
                () -> codec.decode(frameWith(12, payload), PluginMessageDirection.PROXY_TO_BACKEND));
        assertThrows(UnknownMessageKindException.class,
                () -> codec.decode(frameWith(200, payload), PluginMessageDirection.BACKEND_TO_PROXY));
    }

    @Test
    void replyKeepsTheRequestCorrelationId() {
        // the whole pairing scheme: the backend answers on the id the request arrived with
        CaptureRequestMessage request = new CaptureRequestMessage(UUID.randomUUID(), "switch");
        UUID correlation = UUID.randomUUID();
        byte[] requestFrame = codec.encode(request, correlation);
        DecodedPluginMessage decodedRequest = codec.decode(requestFrame, PluginMessageDirection.PROXY_TO_BACKEND);

        CaptureResultMessage result = new CaptureResultMessage(true, 42L, null);
        byte[] resultFrame = codec.encode(result, decodedRequest.correlationId());
        DecodedPluginMessage decodedResult = codec.decode(resultFrame, PluginMessageDirection.BACKEND_TO_PROXY);

        assertEquals(decodedRequest.correlationId(), decodedResult.correlationId());
    }

    private byte[] frameWith(int kind, byte[] payload) {
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES + payload.length];
        System.arraycopy(PluginMessageProtocol.MAGIC, 0, frame, 0, 4);
        frame[4] = (byte) PluginMessageProtocol.CURRENT_VERSION;
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
}
