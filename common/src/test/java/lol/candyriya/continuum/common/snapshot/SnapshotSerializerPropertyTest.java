package lol.candyriya.continuum.common.snapshot;

import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.internal.JacksonMappers;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style coverage: arbitrary byte payloads must roundtrip losslessly
 * (seeded, 100 blobs), and the size guard must trigger at exactly the
 * configured byte limit, not around it.
 */
class SnapshotSerializerPropertyTest {

    private static final int MAX = 4 * 1024 * 1024;
    private static final long SEED = 0x5EED_5EEDL;
    private static final Instant CREATED = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final UUID PLAYER = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private final tools.jackson.databind.json.JsonMapper mapper = JacksonMappers.create();

    private record PayloadCarrier(byte[] blob) {
    }

    private SnapshotEnvelope envelopeWithBlob(byte[] blob) {
        JsonNode payload = mapper.valueToTree(new PayloadCarrier(blob));
        return new SnapshotEnvelope(SnapshotSchema.CURRENT_VERSION, PLAYER, CREATED,
                List.of(new ModuleSnapshot("test", 1, payload)));
    }

    private static byte[] blobBytes(Random random, int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    @Test
    void oneHundredSeededBlobsRoundTripLosslesslyUncompressed() {
        Random random = new Random(SEED);
        SnapshotSerializer serializer = new SnapshotSerializer(new SerializationConfig(false, MAX));

        int[] sizes = blobSizes(random);
        for (int i = 0; i < sizes.length; i++) {
            byte[] blob = blobBytes(random, sizes[i]);
            SnapshotEnvelope decoded = serializer.read(serializer.write(envelopeWithBlob(blob)));
            ModuleSnapshot module = decoded.modules().getFirst();
            assertEquals(Base64.getEncoder().encodeToString(blob), module.payload().get("blob").asString(),
                    "blob #" + i + " (" + sizes[i] + " bytes) did not roundtrip");
        }
        // the seeded size list must have exercised the corner cases, or the seed drifted
        assertTrue(hasSize(sizes, 0) && hasSize(sizes, 1) && hasSize(sizes, 2),
                "seed drifted: the 0/1/2-byte corner cases were supposed to be in the sample");
    }

    @Test
    void oneHundredSeededBlobsRoundTripLosslesslyGzipped() {
        Random random = new Random(SEED);
        SnapshotSerializer serializer = new SnapshotSerializer(new SerializationConfig(true, MAX));

        for (int i = 0; i < 100; i++) {
            int size = switch (i) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 2;
                case 97 -> 512;   // comfortably compressible
                case 98 -> 4_096;
                case 99 -> 262_143; // 256 KiB minus one — big, still under the ceiling
                default -> random.nextInt(0, 2_048);
            };
            byte[] blob = blobBytes(random, size);
            SnapshotEnvelope decoded = serializer.read(serializer.write(envelopeWithBlob(blob)));
            ModuleSnapshot module = decoded.modules().getFirst();
            assertEquals(Base64.getEncoder().encodeToString(blob), module.payload().get("blob").asString(),
                    "gzipped blob #" + i + " (" + size + " bytes) did not roundtrip");
        }
    }

    @Test
    void writeBoundaryIsExactToTheByte() {
        SnapshotSerializer sizing = new SnapshotSerializer(new SerializationConfig(false, MAX));
        byte[] template = sizing.write(envelopeWithBlob(blobBytes(new Random(SEED), 128)));
        int exact = template.length;

        SnapshotSerializer atLimit = new SnapshotSerializer(new SerializationConfig(false, exact));
        SnapshotSerializer oneByteUnder = new SnapshotSerializer(new SerializationConfig(false, exact - 1));

        // same envelope, same size: exact limit must pass, one byte under must not
        assertEquals(exact, atLimit.write(envelopeWithBlob(blobBytes(new Random(SEED), 128))).length);
        assertThrows(SnapshotSerializationException.class,
                () -> oneByteUnder.write(envelopeWithBlob(blobBytes(new Random(SEED), 128))));
    }

    @Test
    void readBoundaryIsExactToTheByte() {
        SnapshotSerializer sizing = new SnapshotSerializer(new SerializationConfig(false, MAX));
        byte[] template = sizing.write(envelopeWithBlob(blobBytes(new Random(SEED), 128)));
        int exact = template.length;

        SnapshotSerializer atLimit = new SnapshotSerializer(new SerializationConfig(false, exact));
        SnapshotSerializer oneByteUnder = new SnapshotSerializer(new SerializationConfig(false, exact - 1));

        assertEquals(PLAYER, atLimit.read(template).playerId());
        assertThrows(SnapshotSerializationException.class, () -> oneByteUnder.read(template));
    }

    @Test
    void gzipWriteBoundaryIsExactToTheByte() {
        SnapshotSerializer sizing = new SnapshotSerializer(new SerializationConfig(true, MAX));
        byte[] template = sizing.write(envelopeWithBlob(blobBytes(new Random(SEED), 128)));
        int storedBytes = template.length;

        SnapshotSerializer atLimit = new SnapshotSerializer(new SerializationConfig(true, storedBytes));
        SnapshotSerializer oneByteUnder = new SnapshotSerializer(new SerializationConfig(true, storedBytes - 1));

        // write side: the stored (compressed) size is what the guard checks
        assertEquals(storedBytes, atLimit.write(envelopeWithBlob(blobBytes(new Random(SEED), 128))).length);
        assertThrows(SnapshotSerializationException.class,
                () -> oneByteUnder.write(envelopeWithBlob(blobBytes(new Random(SEED), 128))));

        // read side, stored-size stage: one byte under the stored size is rejected before decompressing
        assertThrows(SnapshotSerializationException.class, () -> oneByteUnder.read(template));

        // read side, decompressed-size stage: the plain (uncompressed) length is what the
        // decompressor checks for; exactly there must pass, one byte under must not
        int decompressedBytes = new SnapshotSerializer(new SerializationConfig(false, MAX))
                .write(envelopeWithBlob(blobBytes(new Random(SEED), 128))).length;
        assertEquals(PLAYER, new SnapshotSerializer(new SerializationConfig(true, decompressedBytes)).read(template).playerId());
        assertThrows(SnapshotSerializationException.class,
                () -> new SnapshotSerializer(new SerializationConfig(true, decompressedBytes - 1)).read(template));
    }

    @Test
    void emptyOneAndTwoByteBlobsRoundTripGzipped() {
        // tiny payloads are where gzip expansion bugs hide
        SnapshotSerializer serializer = new SnapshotSerializer(new SerializationConfig(true, MAX));
        for (int size = 0; size <= 2; size++) {
            byte[] blob = blobBytes(new Random(SEED + size), size);
            SnapshotEnvelope decoded = serializer.read(serializer.write(envelopeWithBlob(blob)));
            ModuleSnapshot module = decoded.modules().getFirst();
            assertArrayEquals(blob, Base64.getDecoder().decode(module.payload().get("blob").asString()),
                    size + "-byte blob did not survive the gzip roundtrip");
        }
    }

    private static int[] blobSizes(Random random) {
        int[] sizes = new int[100];
        sizes[0] = 0;
        sizes[1] = 1;
        sizes[2] = 2;
        for (int i = 3; i < sizes.length; i++) {
            sizes[i] = switch (i % 10) {
                case 0 -> random.nextInt(0, 16);      // tiny
                case 1 -> random.nextInt(0, 256);     // small
                case 2 -> 4_096 + random.nextInt(0, 4_096); // a few KiB
                default -> random.nextInt(0, 2_048);  // the common case
            };
        }
        return sizes;
    }

    private static boolean hasSize(int[] sizes, int size) {
        for (int s : sizes) {
            if (s == size) {
                return true;
            }
        }
        return false;
    }
}
