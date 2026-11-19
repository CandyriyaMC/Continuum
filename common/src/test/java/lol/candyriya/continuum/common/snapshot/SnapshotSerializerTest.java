package lol.candyriya.continuum.common.snapshot;

import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.internal.JacksonMappers;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotSerializerTest {

    private static final Instant CREATED = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final UUID PLAYER = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final byte[] BLOB = {1, 2, 3, 4, 5, -1, -128, 127};

    private final tools.jackson.databind.json.JsonMapper mapper = JacksonMappers.create();

    private SnapshotEnvelope sampleEnvelope() {
        TestPayload payload = new TestPayload(
                "sword-of-test",
                List.of("tag-a", "tag-b"),
                Map.of("kills", 42, "deaths", 3),
                BLOB,
                Tier.GOLD,
                List.of(Map.of("id", "enchant_1"), Map.of("id", "enchant_2")));
        JsonNode payloadTree = mapper.valueToTree(payload);
        return new SnapshotEnvelope(SnapshotSchema.CURRENT_VERSION, PLAYER, CREATED,
                List.of(new ModuleSnapshot("inventory", 2, payloadTree)));
    }

    private enum Tier {GOLD, SILVER}

    private record TestPayload(String name,
                               List<String> tags,
                               Map<String, Integer> stats,
                               byte[] blob,
                               Tier tier,
                               List<Map<String, String>> items) {
    }

    private static SerializationConfig plainConfig(int maxSnapshotBytes) {
        return new SerializationConfig(false, maxSnapshotBytes);
    }

    @Test
    void roundTripPreservesNestedStructuresBytesAndEnums() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(1024 * 1024));
        SnapshotEnvelope original = sampleEnvelope();

        SnapshotEnvelope decoded = serializer.read(serializer.write(original));

        assertEquals(original.schemaVersion(), decoded.schemaVersion());
        assertEquals(original.playerId(), decoded.playerId());
        assertEquals(CREATED, decoded.createdAt());
        assertEquals(1, decoded.modules().size());
        ModuleSnapshot module = decoded.modules().get(0);
        assertEquals("inventory", module.moduleId());
        assertEquals(2, module.moduleVersion());
        // field-by-field: byte[] lands in the tree as a BinaryNode on write but as text on read,
        // so a whole-tree equals() would always fail — assert the parts instead
        assertEquals(List.of("tag-a", "tag-b"), List.of(
                module.payload().get("tags").get(0).asString(),
                module.payload().get("tags").get(1).asString()));
        assertEquals("enchant_1", module.payload().get("items").get(0).get("id").asString());
        assertEquals("enchant_2", module.payload().get("items").get(1).get("id").asString());
        assertEquals(3, module.payload().get("stats").get("deaths").asInt());
        // the nested bits that tend to break first:
        assertEquals("sword-of-test", module.payload().get("name").asString());
        assertEquals(2, module.payload().get("items").size());
        assertEquals(Base64.getEncoder().encodeToString(BLOB), module.payload().get("blob").asString());
        assertEquals("GOLD", module.payload().get("tier").asString());
        assertEquals(42, module.payload().get("stats").get("kills").asInt());
    }

    @Test
    void wireFormatUsesSnakeCaseStableKeys() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(1024 * 1024));
        String json = new String(serializer.write(sampleEnvelope()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema_version\""), json);
        assertTrue(json.contains("\"player_id\""), json);
        assertTrue(json.contains("\"created_at\""), json);
        assertTrue(json.contains("\"module_id\""), json);
        assertTrue(json.contains("\"module_version\""), json);
        assertFalse(json.contains("\"schemaVersion\""), json);
    }

    @Test
    void byteArraysSerializeAsBase64() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(1024 * 1024));
        String json = new String(serializer.write(sampleEnvelope()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains(Base64.getEncoder().encodeToString(BLOB)), json);
    }

    @Test
    void gzipRoundTripWorks() {
        SnapshotSerializer serializer = new SnapshotSerializer(new SerializationConfig(true, 1024 * 1024));
        byte[] stored = serializer.write(sampleEnvelope());
        assertEquals((byte) 0x1f, stored[0], "gzip magic byte 0");
        assertEquals((byte) 0x8b, stored[1], "gzip magic byte 1");
        assertEquals(PLAYER, serializer.read(stored).playerId());
    }

    @Test
    void gzipWrittenDataReadsBackWithGzipDisabled() {
        // operator flips gzip off on next deploy — old snapshots must still open
        SnapshotSerializer writer = new SnapshotSerializer(new SerializationConfig(true, 1024 * 1024));
        SnapshotSerializer reader = new SnapshotSerializer(plainConfig(1024 * 1024));
        SnapshotEnvelope decoded = reader.read(writer.write(sampleEnvelope()));
        assertEquals(PLAYER, decoded.playerId());
    }

    @Test
    void plainWrittenDataReadsBackWithGzipEnabled() {
        SnapshotSerializer writer = new SnapshotSerializer(plainConfig(1024 * 1024));
        SnapshotSerializer reader = new SnapshotSerializer(new SerializationConfig(true, 1024 * 1024));
        SnapshotEnvelope decoded = reader.read(writer.write(sampleEnvelope()));
        assertEquals(PLAYER, decoded.playerId());
    }

    @Test
    void writeRejectsSnapshotExceedingLimit() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(64));
        SnapshotSerializationException e = assertThrows(SnapshotSerializationException.class,
                () -> serializer.write(sampleEnvelope()));
        assertTrue(e.getMessage().contains("64"));
        assertEquals("serialization.failed", e.code());
    }

    @Test
    void readRejectsStoredSnapshotExceedingLimit() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(64));
        byte[] tooBig = new byte[65];
        SnapshotSerializationException e = assertThrows(SnapshotSerializationException.class,
                () -> serializer.read(tooBig));
        assertTrue(e.getMessage().contains("65"));
    }

    @Test
    void readRejectsGzipBombExceedingDecompressedLimit() {
        // build a gzip blob whose decompressed size is way over the guard
        String hugeJson = "{\"schema_version\":1,\"player_id\":\"" + PLAYER + "\",\"created_at\":1,\"modules\":[],\"padding\":\""
                + "x".repeat(200_000) + "\"}";
        byte[] gzipped;
        try (var out = new java.io.ByteArrayOutputStream();
             var gzipOut = new java.util.zip.GZIPOutputStream(out)) {
            gzipOut.write(hugeJson.getBytes());
            gzipOut.finish();
            gzipped = out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(10_000));
        assertThrows(SnapshotSerializationException.class, () -> serializer.read(gzipped));
    }

    @Test
    void tamperedBytesAreRejected() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(1024 * 1024));
        assertThrows(SnapshotSerializationException.class,
                () -> serializer.read(new byte[]{'n', 'o', 't', ' ', 'j', 's', 'o', 'n'}));
    }

    @Test
    void emptyPayloadIsRejected() {
        SnapshotSerializer serializer = new SnapshotSerializer(plainConfig(1024 * 1024));
        assertThrows(SnapshotSerializationException.class, () -> serializer.read(new byte[0]));
    }

    @Test
    void duplicateModuleIdsAreRejected() {
        ModuleSnapshot module = new ModuleSnapshot("inventory", 1, mapper.createObjectNode());
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotEnvelope(1, PLAYER, CREATED, List.of(module, module)));
    }

    @Test
    void moduleVersionBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleSnapshot("inventory", 0, mapper.createObjectNode()));
    }

    @Test
    void schemaVersionBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotEnvelope(0, PLAYER, CREATED, List.of()));
    }

    @Test
    void readViaMigrationRunnerUpgradesDocument() {
        // chain: v1 -> v2 renames module "stats" to "counters", v2 -> v3 drops a legacy field
        SnapshotMigration v1ToV2 = new RenameModuleMigration();
        SnapshotMigration v2ToV3 = new DropLegacyFieldMigration();
        var runner = new SnapshotMigrationRunner(List.of(v1ToV2, v2ToV3), 3);
        var serializer = new SnapshotSerializer(plainConfig(1024 * 1024), runner);

        String legacy = """
                {"schema_version":1,"player_id":"%s","created_at":%d,"modules":[{"module_id":"stats","module_version":1,"payload":{"k":"v"}}],"legacy_junk":"x"}
                """.formatted(PLAYER, CREATED.toEpochMilli());
        SnapshotEnvelope envelope = serializer.read(legacy.getBytes());

        assertEquals(3, envelope.schemaVersion());
        assertEquals("counters", envelope.modules().get(0).moduleId());
    }

    private static final class RenameModuleMigration implements SnapshotMigration {
        @Override
        public int fromVersion() {
            return 1;
        }

        @Override
        public JsonNode migrate(JsonNode snapshot) {
            var object = (tools.jackson.databind.node.ObjectNode) snapshot;
            var modules = (tools.jackson.databind.node.ArrayNode) object.get("modules");
            var first = (tools.jackson.databind.node.ObjectNode) modules.get(0);
            if ("stats".equals(first.get("module_id").asString())) {
                first.put("module_id", "counters");
            }
            return SnapshotMigrationRunner.withSchemaVersion(object, toVersion());
        }
    }

    private static final class DropLegacyFieldMigration implements SnapshotMigration {
        @Override
        public int fromVersion() {
            return 2;
        }

        @Override
        public JsonNode migrate(JsonNode snapshot) {
            var object = (tools.jackson.databind.node.ObjectNode) snapshot.deepCopy();
            object.remove("legacy_junk");
            return SnapshotMigrationRunner.withSchemaVersion(object, toVersion());
        }
    }
}


