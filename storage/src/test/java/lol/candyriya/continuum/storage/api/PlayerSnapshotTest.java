package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSnapshotTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static ModuleSnapshot module(String id) {
        JsonNode payload = MAPPER.readTree("{\"marker\":\"" + id + "\"}");
        return new ModuleSnapshot(id, 1, payload);
    }

    private static SnapshotEnvelope envelope(UUID player, String... moduleIds) {
        List<ModuleSnapshot> modules = java.util.Arrays.stream(moduleIds).map(PlayerSnapshotTest::module).toList();
        return new SnapshotEnvelope(1, player, Instant.now(), modules);
    }

    @Test
    void derivedAccessorsComeFromTheEnvelope() {
        UUID player = UUID.randomUUID();
        PlayerSnapshot snapshot = new PlayerSnapshot(envelope(player, "vitals", "inventory"), 41L, Instant.now());
        assertEquals(player, snapshot.playerId());
        assertEquals(1, snapshot.schemaVersion());
        assertEquals(List.of("vitals", "inventory"), snapshot.moduleIds());
        assertEquals(41L, snapshot.fencingToken());
    }

    @Test
    void nullEnvelopeIsRejected() {
        assertThrows(NullPointerException.class, () -> new PlayerSnapshot(null, 0, Instant.now()));
    }

    @Test
    void negativeFencingTokenIsRejected() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerSnapshot(envelope(player, "vitals"), -1, Instant.now()));
    }

    @Test
    void nullSavedAtIsRejected() {
        UUID player = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new PlayerSnapshot(envelope(player, "vitals"), 0, null));
    }

    @Test
    void emptyModuleListIsFine() {
        UUID player = UUID.randomUUID();
        PlayerSnapshot snapshot = new PlayerSnapshot(envelope(player), 0, Instant.now());
        assertTrue(snapshot.moduleIds().isEmpty());
    }
}
