package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotSerializer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The envelope this pipeline builds must survive the exact codec storage uses.
 * Payload trees are mapped through the shared wire mapper, serialized with the
 * common SnapshotSerializer and read back — the same round trip the SQL/Mongo
 * implementations perform on save and load.
 */
class SnapshotRoundtripTest {

    private static final JsonMapper MAPPER = JacksonMappers.create();

    @Test
    void envelopeSurvivesTheStorageCodecRoundtrip() {
        UUID playerId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        JsonNode payload = MAPPER.valueToTree(Map.of("marker", "value", "count", 42));

        SnapshotEnvelope built = SnapshotAssembler.merge(playerId, createdAt,
                List.of(BuiltinModules.VITALS, BuiltinModules.INVENTORY),
                List.of(new SnapshotAssembler.CapturedPayload(BuiltinModules.VITALS, 2, payload)),
                null);

        SnapshotSerializer serializer = new SnapshotSerializer(new SerializationConfig(false, 1024 * 1024));
        byte[] bytes = serializer.write(built);
        SnapshotEnvelope back = serializer.read(bytes);

        assertEquals(built.schemaVersion(), back.schemaVersion());
        assertEquals(playerId, back.playerId());
        assertEquals(1, back.modules().size());
        var module = back.modules().getFirst();
        assertEquals(BuiltinModules.VITALS, module.moduleId());
        assertEquals(2, module.moduleVersion());
        assertEquals("value", module.payload().get("marker").asString());
        assertEquals(42, module.payload().get("count").asInt());
    }

    @Test
    void envelopeRoundtripsThroughGzipToo() {
        UUID playerId = UUID.randomUUID();
        JsonNode payload = MAPPER.valueToTree(Map.of("marker", "gzipped"));

        SnapshotEnvelope built = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of(BuiltinModules.INVENTORY),
                List.of(new SnapshotAssembler.CapturedPayload(BuiltinModules.INVENTORY, 1, payload)),
                null);

        // storage writes whatever the config says; both directions must work
        SnapshotSerializer writer = new SnapshotSerializer(new SerializationConfig(true, 1024 * 1024));
        SnapshotSerializer reader = new SnapshotSerializer(new SerializationConfig(false, 1024 * 1024));
        byte[] bytes = writer.write(built);
        // auto-detected by magic bytes on read, even with gzip off in config
        SnapshotEnvelope back = reader.read(bytes);

        assertNotNull(back);
        assertEquals("gzipped", back.modules().getFirst().payload().get("marker").asString());
        assertTrue(bytes.length > 2);
    }
}
