package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The collect-all validation API. Fail-fast decode is tested elsewhere;
 * here we care that validate() reports everything it can find, in order,
 * without throwing for bad input.
 */
class PluginMessageValidatorTest {

    private final PluginMessageValidator validator = new PluginMessageValidator();
    private final PluginMessageCodec codec = new PluginMessageCodec();

    private byte[] validFrame() {
        return codec.encode(new PingMessage(Instant.ofEpochMilli(1_700_000_000_000L)), UUID.randomUUID());
    }

    @Test
    void validFrameHasNoErrors() {
        for (PluginMessageKind kind : PluginMessageKind.values()) {
            PluginMessage message = PluginMessageCodecRoundTripTest.sampleOf(kind);
            byte[] frame = codec.encode(message, UUID.randomUUID());
            assertEquals(List.of(), validator.validate(frame, kind.direction()), "kind " + kind);
        }
    }

    @Test
    void oversizeFrameReportsSingleTerminalError() {
        byte[] huge = new byte[PluginMessageLimits.DEFAULT_MAX_FRAME_BYTES + 1];
        List<PluginMessageException> errors = validator.validate(huge);
        assertEquals(1, errors.size());
        assertInstanceOf(FrameTooLargeException.class, errors.get(0));
    }

    @Test
    void nullFrameIsRejectedLoudly() {
        assertThrows(NullPointerException.class, () -> validator.validate((byte[]) null));
    }

    @Test
    void multipleErrorsAreCollectedInDetectionOrder() {
        byte[] frame = validFrame();
        frame[0] = 'X'; // bad magic
        frame[4] = 0x63; // unsupported version
        frame[5] = (byte) 200; // unknown kind
        List<PluginMessageException> errors = validator.validate(frame, PluginMessageDirection.BACKEND_TO_PROXY);

        assertTrue(errors.stream().anyMatch(e -> e instanceof InvalidMagicException));
        assertTrue(errors.stream().anyMatch(e -> e instanceof UnsupportedProtocolVersionException));
        assertTrue(errors.stream().anyMatch(e -> e instanceof UnknownMessageKindException));
        // detection order: magic before version before kind
        int magicIndex = indexOf(errors, InvalidMagicException.class);
        int versionIndex = indexOf(errors, UnsupportedProtocolVersionException.class);
        int kindIndex = indexOf(errors, UnknownMessageKindException.class);
        assertTrue(magicIndex < versionIndex && versionIndex < kindIndex);
    }

    @Test
    void trailingBytesAndSemanticErrorsAreBothReported() {
        byte[] payload = ("{\"player\":\"00000000-0000-0000-0000-000000000000\","
                + "\"target_server\":\"survival-1\",\"reason\":\"\"}").getBytes(StandardCharsets.UTF_8);
        UUID correlation = UUID.randomUUID();
        // build the frame by hand so the declared length stops short of the real bytes
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES + payload.length + 1];
        System.arraycopy(PluginMessageProtocol.MAGIC, 0, frame, 0, 4);
        frame[4] = (byte) PluginMessageProtocol.CURRENT_VERSION;
        frame[5] = (byte) PluginMessageKind.HANDOFF_REQUEST.id();
        long msb = correlation.getMostSignificantBits();
        long lsb = correlation.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            frame[6 + i] = (byte) ((msb >>> ((7 - i) * 8)) & 0xff);
            frame[14 + i] = (byte) ((lsb >>> ((7 - i) * 8)) & 0xff);
        }
        int declared = payload.length;
        frame[22] = (byte) ((declared >>> 24) & 0xff);
        frame[23] = (byte) ((declared >>> 16) & 0xff);
        frame[24] = (byte) ((declared >>> 8) & 0xff);
        frame[25] = (byte) (declared & 0xff);
        System.arraycopy(payload, 0, frame, PluginMessageProtocol.HEADER_BYTES, payload.length);
        frame[frame.length - 1] = 0x00; // trailing garbage

        List<PluginMessageException> errors = validator.validate(frame, PluginMessageDirection.BACKEND_TO_PROXY);
        assertTrue(errors.stream().anyMatch(e -> e instanceof TrailingBytesException));
        assertTrue(errors.stream().anyMatch(e -> e instanceof PluginMessageValidationException));
    }

    @Test
    void directionMismatchIsReportedWhenDirectionExpected() {
        byte[] frame = validFrame(); // a ping: BACKEND_TO_PROXY kind
        List<PluginMessageException> errors = validator.validate(frame, PluginMessageDirection.PROXY_TO_BACKEND);
        assertEquals(1, errors.size());
        DirectionMismatchException e = assertInstanceOf(DirectionMismatchException.class, errors.get(0));
        assertEquals(PluginMessageDirection.PROXY_TO_BACKEND, e.expected());
        assertEquals(PluginMessageDirection.BACKEND_TO_PROXY, e.actual());
    }

    @Test
    void directionAgnosticValidateIgnoresDirection() {
        byte[] frame = validFrame();
        assertEquals(List.of(), validator.validate(frame));
    }

    @Test
    void malformedJsonIsReportedNotThrown() {
        byte[] payload = "{\"sent_at\":".getBytes(StandardCharsets.UTF_8);
        byte[] base = codec.encode(new PingMessage(Instant.ofEpochMilli(1_700_000_000_000L)), UUID.randomUUID());
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES + payload.length];
        System.arraycopy(base, 0, frame, 0, PluginMessageProtocol.HEADER_BYTES);
        frame[22] = (byte) ((payload.length >>> 24) & 0xff);
        frame[23] = (byte) ((payload.length >>> 16) & 0xff);
        frame[24] = (byte) ((payload.length >>> 8) & 0xff);
        frame[25] = (byte) (payload.length & 0xff);
        System.arraycopy(payload, 0, frame, PluginMessageProtocol.HEADER_BYTES, payload.length);

        List<PluginMessageException> errors = validator.validate(frame, PluginMessageDirection.BACKEND_TO_PROXY);
        assertEquals(1, errors.size());
        assertInstanceOf(MalformedPayloadException.class, errors.get(0));
    }

    @Test
    void invalidUtf8IsReportedNotThrown() {
        byte[] payload = {(byte) 0xC3, 0x28};
        byte[] base = codec.encode(new PingMessage(Instant.ofEpochMilli(1_700_000_000_000L)), UUID.randomUUID());
        byte[] frame = new byte[PluginMessageProtocol.HEADER_BYTES + payload.length];
        System.arraycopy(base, 0, frame, 0, PluginMessageProtocol.HEADER_BYTES);
        frame[22] = 0;
        frame[23] = 0;
        frame[24] = 0;
        frame[25] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, PluginMessageProtocol.HEADER_BYTES, payload.length);

        List<PluginMessageException> errors = validator.validate(frame, PluginMessageDirection.BACKEND_TO_PROXY);
        assertTrue(errors.stream().anyMatch(e -> e instanceof InvalidUtf8Exception));
    }

    @Test
    void semanticViolationsAreCollectedAcrossFields() {
        HandoffRequestMessage broken =
                new HandoffRequestMessage(new UUID(0, 0), "NOT A SERVER", "x".repeat(257));
        List<PluginMessageException> errors = validator.validate(broken);
        assertEquals(1, errors.size());
        PluginMessageValidationException e = assertInstanceOf(PluginMessageValidationException.class, errors.get(0));
        assertEquals(3, e.violations().size());
    }

    @Test
    void semanticallyInvalidPingIsReported() {
        PingMessage broken = new PingMessage(Instant.ofEpochMilli(PluginMessageConstraints.MAX_INSTANT_EPOCH_MILLI + 1));
        assertEquals(1, validator.validate(broken).size());
    }

    @Test
    void routeResultAllowedRequiresTarget() {
        UUID player = UUID.randomUUID();
        RouteResultMessage missingTarget =
                new RouteResultMessage(player, RouteOutcome.ALLOWED, "", "no target");
        assertEquals(1, validator.validate(missingTarget).size());

        RouteResultMessage allowed =
                new RouteResultMessage(player, RouteOutcome.ALLOWED, "survival-1", "ok");
        assertEquals(List.of(), validator.validate(allowed));

        RouteResultMessage deniedNoTarget =
                new RouteResultMessage(player, RouteOutcome.DENIED, "", "policy");
        assertEquals(List.of(), validator.validate(deniedNoTarget));
    }

    @Test
    void sessionTokenRulesAreEnforced() {
        UUID player = UUID.randomUUID();
        Instant inRange = Instant.ofEpochMilli(2_000_000_000_000L);

        assertEquals(List.of(),
                validator.validate(new SessionTokenMessage(player, "opaque-token_ABC123", inRange)));
        assertEquals(1, validator.validate(new SessionTokenMessage(player, "has space", inRange)).size());
        assertEquals(1, validator.validate(new SessionTokenMessage(player, "tøken", inRange)).size());
        assertEquals(1, validator.validate(new SessionTokenMessage(player, "x".repeat(513), inRange)).size());
        assertEquals(1, validator.validate(
                new SessionTokenMessage(player, "ok", Instant.ofEpochMilli(PluginMessageConstraints.MAX_INSTANT_EPOCH_MILLI))).size());
        assertEquals(1, validator.validate(
                new SessionTokenMessage(player, "ok", Instant.ofEpochMilli(-1))).size());
    }

    @Test
    void backendStatusCountRulesAreEnforced() {
        assertEquals(List.of(), validator.validate(
                new BackendStatusMessage("survival-1", 0, 1, true, "")));
        assertEquals(1, validator.validate(
                new BackendStatusMessage("survival-1", 101, 100, true, "")).size()); // over capacity
        assertEquals(1, validator.validate(
                new BackendStatusMessage("survival-1", -1, 100, true, "")).size()); // negative
        assertEquals(1, validator.validate(
                new BackendStatusMessage("survival-1", 0, 0, true, "")).size());    // no capacity
        assertEquals(1, validator.validate(
                new BackendStatusMessage("survival-1", 0, 1_000_001, true, "")).size());
        assertEquals(1, validator.validate(
                new BackendStatusMessage("bad name!", 0, 100, true, "")).size());
    }

    @Test
    void localeRulesAreEnforced() {
        UUID player = UUID.randomUUID();
        assertEquals(List.of(), validator.validate(new LocaleSetMessage(player, "en_US")));
        assertEquals(List.of(), validator.validate(new LocaleSetMessage(player, "zh-CN")));
        assertEquals(List.of(), validator.validate(new LocaleSetMessage(player, "kk-Cyrl")));
        assertEquals(1, validator.validate(new LocaleSetMessage(player, "e")).size());
        assertEquals(1, validator.validate(new LocaleSetMessage(player, "1234")).size());
        assertEquals(1, validator.validate(new LocaleSetMessage(player, "en--US")).size());
        assertEquals(1, validator.validate(new LocaleSetMessage(player, "x".repeat(36))).size());
    }

    @Test
    void logTextForbidsControlCharacters() {
        UUID player = UUID.randomUUID();
        HandoffResumedMessage withBell = new HandoffResumedMessage(player, "survival-1", "done\u0007bell");
        assertEquals(1, validator.validate(withBell).size());
        HandoffResumedMessage clean = new HandoffResumedMessage(player, "survival-1", "all good");
        assertEquals(List.of(), validator.validate(clean));
        HandoffResumedMessage tooLong = new HandoffResumedMessage(player, "survival-1", "x".repeat(257));
        assertEquals(1, validator.validate(tooLong).size());
    }

    private static int indexOf(List<PluginMessageException> errors, Class<? extends PluginMessageException> type) {
        for (int i = 0; i < errors.size(); i++) {
            if (type.isInstance(errors.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
