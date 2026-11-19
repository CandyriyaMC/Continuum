package lol.candyriya.continuum.common.protocol;

import lol.candyriya.continuum.common.protocol.payload.CoreEventTypes;
import lol.candyriya.continuum.common.protocol.payload.SessionReleasedPayload;
import lol.candyriya.continuum.common.protocol.payload.TransferCommittedPayload;
import lol.candyriya.continuum.common.protocol.payload.TransferFailedPayload;
import lol.candyriya.continuum.common.protocol.payload.TransferRequestedPayload;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style coverage for the wire codec: seeded valid envelopes roundtrip
 * losslessly, and single-bit corruption is never silently accepted.
 *
 * <p>The envelope format carries no checksum or signature, so a flipped bit
 * inside an opaque string field is indistinguishable from a legitimate
 * different string — that is by design (the transport is authenticated
 * elsewhere). The honest invariant is therefore: every single-bit corruption
 * either gets rejected with a typed {@link ProtocolException}, or comes back
 * as a <em>different</em> envelope. Silent acceptance exists in exactly one
 * pinned form: ASCII case flips on hex letters inside UUID fields, which
 * {@code UUID.fromString} parses to the same UUID — the sweep test pins that
 * bucket by position and count. Everything else is a rejection or a visible
 * mutation. Everything outside the JSON framing that is type-critical is
 * always a typed rejection; the test asserts that too, for the structural
 * prefix.</p>
 */
class EnvelopeCodecPropertyTest {

    private static final int MAX_BYTES = 4 * 1024;
    private static final long SEED = 0xF1E1DL;
    private static final Instant FIXED_TS = Instant.ofEpochMilli(1_700_000_000_000L);

    private final MessageTypeRegistry registry = CoreEventTypes.create();
    private final EnvelopeCodec codec = new EnvelopeCodec(registry, MAX_BYTES);

    /** Any valid envelope roundtrips — 100 seeded cases across all registered payload types. */
    @Test
    void oneHundredSeededEnvelopesRoundTripLosslessly() {
        Random random = new Random(SEED);
        for (int i = 0; i < 100; i++) {
            MessageEnvelope<? extends EventPayload> original = randomEnvelope(random, i % 4);
            byte[] frame = codec.encode(original);
            MessageEnvelope<JsonNode> decodedRaw = codec.decodeUntyped(frame);

            assertEquals(original.protocolVersion(), decodedRaw.protocolVersion());
            assertEquals(original.eventType(), decodedRaw.eventType());
            assertEquals(original.eventId(), decodedRaw.eventId());
            assertEquals(original.timestamp(), decodedRaw.timestamp());
            assertEquals(original.correlationId(), decodedRaw.correlationId());
            assertEquals(mapper().valueToTree(original.payload()), decodedRaw.payload());

            // and the typed decode path yields an equal envelope
            assertTypedRoundTrip(original);
        }
    }

    /**
     * Every bit of the JSON framing prefix must be rejected: flip each of the
     * 64 bits in the first 8 bytes ({@code {"protocol}) and demand a typed
     * rejection. Deterministic, one frame, 64 assertions.
     */
    @Test
    void everyBitOfTheFramingPrefixIsRejectedWithATypedException() {
        MessageEnvelope<TransferRequestedPayload> original = fixedEnvelope();
        byte[] frame = codec.encode(original);

        int rejected = 0;
        for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] corrupted = frame.clone();
                corrupted[byteIndex] ^= (byte) (1 << bit);
                assertThrows(ProtocolException.class,
                        () -> codec.decode(corrupted, TransferRequestedPayload.class),
                        "framing byte " + byteIndex + " bit " + bit + " must be rejected");
                rejected++;
            }
        }
        assertEquals(64, rejected);
    }

    /**
     * Full sweep over every single-bit corruption of a fixed valid frame.
     * Every flip must either throw a typed exception, yield a different
     * envelope, or land in the one known silently-accepted bucket: ASCII case
     * flips ({@code 0x20}) on hex letters {@code a-f} inside UUID fields,
     * which {@code UUID.fromString} parses to the same UUID.
     *
     * <p>That bucket is pinned exactly — the frame, the byte positions and
     * the flip count are all deterministic — so a new silent-acceptance path
     * cannot sneak in without this test going red. Byte-level corruption
     * outside UUID hex is never silently accepted.</p>
     */
    @Test
    void noSingleBitCorruptionIsSilentlyAcceptedExceptDocumentedUuidCaseFlips() {
        MessageEnvelope<TransferRequestedPayload> original = fixedEnvelope();
        byte[] frame = codec.encode(original);

        int uuidStart = indexOf(frame, "3fa85f64-5717-4562-b3fc-2c963f66afa6".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int uuidEnd = uuidStart + "3fa85f64-5717-4562-b3fc-2c963f66afa6".length();

        int rejected = 0;
        int mutated = 0;
        List<int[]> acceptedEqual = new ArrayList<>();
        int total = frame.length * 8;
        for (int byteIndex = 0; byteIndex < frame.length; byteIndex++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] corrupted = frame.clone();
                corrupted[byteIndex] ^= (byte) (1 << bit);
                try {
                    MessageEnvelope<TransferRequestedPayload> decoded =
                            codec.decode(corrupted, TransferRequestedPayload.class);
                    if (original.equals(decoded)) {
                        acceptedEqual.add(new int[]{byteIndex, bit});
                    } else {
                        mutated++;
                    }
                } catch (ProtocolException expected) {
                    rejected++;
                }
            }
        }
        assertEquals(total, rejected + mutated + acceptedEqual.size(),
                "every one of the " + total + " flips must land in exactly one bucket");

        int expectedSilent = 0;
        for (int i = uuidStart; i < uuidEnd; i++) {
            char c = (char) (frame[i] & 0xff);
            if ("abcdef".indexOf(c) >= 0) {
                expectedSilent++;
            }
        }
        for (int[] position : acceptedEqual) {
            int byteIndex = position[0];
            int bit = position[1];
            assertEquals(5, bit, "only a 0x20 (ASCII case) flip may be silently accepted, got bit " + bit
                    + " at byte " + byteIndex);
            assertTrue(byteIndex >= uuidStart && byteIndex < uuidEnd,
                    "silently accepted flip outside the event_id UUID span: byte " + byteIndex);
            assertTrue("abcdef".indexOf((char) (frame[byteIndex] & 0xff)) >= 0,
                    "silently accepted flip on a non-hex letter: byte " + byteIndex);
        }
        assertEquals(expectedSilent, acceptedEqual.size(),
                "exactly the lowercase hex letters in the event_id UUID may decode to the same UUID");
        assertTrue(expectedSilent > 0, "fixture sanity: the UUID case-flip bucket must be non-empty");

        // sanity: if almost every flip silently mutated and nothing were rejected,
        // the codec would be doing no structural checking at all
        assertTrue(rejected > total / 5,
                "suspiciously few flips rejected (" + rejected + " of " + total + ") — structural validation broken?");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        throw new AssertionError("fixture UUID not found in frame");
    }

    private static tools.jackson.databind.json.JsonMapper mapper() {
        return lol.candyriya.continuum.common.internal.JacksonMappers.create();
    }

    private static MessageEnvelope<TransferRequestedPayload> fixedEnvelope() {
        return new MessageEnvelope<>(
                Protocol.CURRENT_VERSION,
                ProtocolEvents.TRANSFER_REQUESTED,
                UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
                FIXED_TS,
                "corr-fixed-0001",
                new TransferRequestedPayload(UUID.fromString("11111111-2222-3333-4444-555555555555"),
                        "survival-1", "lobby-1", 41));
    }

    @SuppressWarnings("unchecked")
    private <P extends EventPayload> void assertTypedRoundTrip(MessageEnvelope<P> original) {
        byte[] frame = codec.encode(original);
        switch (original.eventType()) {
            case ProtocolEvents.TRANSFER_REQUESTED ->
                    assertEquals((MessageEnvelope<TransferRequestedPayload>) original,
                            codec.decode(frame, TransferRequestedPayload.class));
            case ProtocolEvents.TRANSFER_COMMITTED ->
                    assertEquals((MessageEnvelope<TransferCommittedPayload>) original,
                            codec.decode(frame, TransferCommittedPayload.class));
            case ProtocolEvents.TRANSFER_FAILED ->
                    assertEquals((MessageEnvelope<TransferFailedPayload>) original,
                            codec.decode(frame, TransferFailedPayload.class));
            case ProtocolEvents.SESSION_RELEASED ->
                    assertEquals((MessageEnvelope<SessionReleasedPayload>) original,
                            codec.decode(frame, SessionReleasedPayload.class));
            default -> throw new IllegalStateException("unregistered event type in test fixture: " + original.eventType());
        }
    }

    private static MessageEnvelope<? extends EventPayload> randomEnvelope(Random random, int kind) {
        UUID eventId = new UUID(random.nextLong(), random.nextLong());
        Instant timestamp = Instant.ofEpochMilli(FIXED_TS.toEpochMilli() - random.nextInt(0, 86_400_000));
        String correlationId = "corr-" + Long.toHexString(random.nextLong());
        return switch (kind) {
            case 0 -> new MessageEnvelope<>(Protocol.CURRENT_VERSION, ProtocolEvents.TRANSFER_REQUESTED,
                    eventId, timestamp, correlationId,
                    new TransferRequestedPayload(randomUuid(random), "from-" + random.nextInt(0, 999),
                            "to-" + random.nextInt(0, 999), random.nextLong()));
            case 1 -> new MessageEnvelope<>(Protocol.CURRENT_VERSION, ProtocolEvents.TRANSFER_COMMITTED,
                    eventId, timestamp, correlationId,
                    new TransferCommittedPayload(randomUuid(random), randomUuid(random), random.nextLong()));
            case 2 -> new MessageEnvelope<>(Protocol.CURRENT_VERSION, ProtocolEvents.TRANSFER_FAILED,
                    eventId, timestamp, correlationId,
                    new TransferFailedPayload(randomUuid(random), randomUuid(random), "cause-" + random.nextInt(0, 999)));
            case 3 -> new MessageEnvelope<>(Protocol.CURRENT_VERSION, ProtocolEvents.SESSION_RELEASED,
                    eventId, timestamp, correlationId,
                    new SessionReleasedPayload(randomUuid(random), "server-" + random.nextInt(0, 999), random.nextLong()));
            default -> throw new IllegalStateException("kind out of range");
        };
    }

    private static UUID randomUuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }
}
