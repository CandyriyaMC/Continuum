package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotAssemblerTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private static JsonNode payload(String marker) {
        return MAPPER.valueToTree(java.util.Map.of("marker", marker));
    }

    private static ModuleSnapshot moduleSnapshot(String id, String marker) {
        return new ModuleSnapshot(id, MODULE_VERSION, payload(marker));
    }

    private static final int MODULE_VERSION = 3;

    private static SnapshotEnvelope lkg(UUID playerId, ModuleSnapshot... modules) {
        return new SnapshotEnvelope(SnapshotSchema.CURRENT_VERSION, playerId, Instant.now(), List.of(modules));
    }

    @Test
    void freshCaptureProducesAnEnvelopeWithTheCapturedModules() {
        UUID playerId = UUID.randomUUID();

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory", "continuum.vitals"),
                List.of(new SnapshotAssembler.CapturedPayload("continuum.inventory", 1, payload("fresh-inventory")),
                        new SnapshotAssembler.CapturedPayload("continuum.vitals", 1, payload("fresh-vitals"))),
                null);

        assertNotNull(merged);
        assertEquals(SnapshotSchema.CURRENT_VERSION, merged.schemaVersion());
        assertEquals(playerId, merged.playerId());
        assertEquals(List.of("continuum.inventory", "continuum.vitals"),
                merged.modules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void mergeCarriesOverUntouchedModulesFromTheLastKnownGood() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope good = lkg(playerId,
                moduleSnapshot("continuum.inventory", "good-inventory"),
                moduleSnapshot("continuum.vitals", "good-vitals"));

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory", "continuum.vitals"),
                List.of(new SnapshotAssembler.CapturedPayload("continuum.vitals", 1, payload("fresh-vitals"))),
                good);

        assertNotNull(merged);
        ModuleSnapshot inventory = merged.modules().stream()
                .filter(m -> m.moduleId().equals("continuum.inventory")).findFirst().orElseThrow();
        ModuleSnapshot vitals = merged.modules().stream()
                .filter(m -> m.moduleId().equals("continuum.vitals")).findFirst().orElseThrow();
        assertEquals("good-inventory", inventory.payload().get("marker").asString());
        assertEquals("fresh-vitals", vitals.payload().get("marker").asString());
    }

    @Test
    void freshPayloadWinsOverTheLastKnownGoodEntry() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope good = lkg(playerId, moduleSnapshot("continuum.inventory", "old"));

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory"),
                List.of(new SnapshotAssembler.CapturedPayload("continuum.inventory", 1, payload("new"))),
                good);

        assertNotNull(merged);
        assertEquals(1, merged.modules().size());
        assertEquals("new", merged.modules().getFirst().payload().get("marker").asString());
    }

    @Test
    void disabledModulesAreDroppedEvenWhenPresentInTheLastKnownGood() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope good = lkg(playerId,
                moduleSnapshot("continuum.inventory", "good-inventory"),
                moduleSnapshot("continuum.vitals", "good-vitals"));

        // vittals was disabled after the LKG was written (enabled list no longer contains it)
        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory"),
                List.of(),
                good);

        assertNotNull(merged);
        assertEquals(List.of("continuum.inventory"),
                merged.modules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void enabledModuleMissingFromBothSnapshotsIsSimplyAbsent() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope good = lkg(playerId, moduleSnapshot("continuum.inventory", "good-inventory"));

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory", "continuum.recipes"),
                List.of(),
                good);

        assertNotNull(merged);
        assertEquals(List.of("continuum.inventory"),
                merged.modules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void emptyMergeReturnsNull() {
        UUID playerId = UUID.randomUUID();

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory"), List.of(), null);

        assertNull(merged);
    }

    @Test
    void moduleOrderInTheEnvelopeFollowsTheEnabledList() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope good = lkg(playerId,
                moduleSnapshot("continuum.vitals", "good-vitals"),
                moduleSnapshot("continuum.inventory", "good-inventory"));

        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory", "continuum.vitals"),
                List.of(),
                good);

        assertNotNull(merged);
        assertEquals(List.of("continuum.inventory", "continuum.vitals"),
                merged.modules().stream().map(ModuleSnapshot::moduleId).toList());
    }

    @Test
    void moduleVersionRoundTrips() {
        UUID playerId = UUID.randomUUID();
        SnapshotEnvelope merged = SnapshotAssembler.merge(playerId, Instant.now(),
                List.of("continuum.inventory"),
                List.of(new SnapshotAssembler.CapturedPayload("continuum.inventory", MODULE_VERSION, payload("v"))),
                null);

        assertNotNull(merged);
        assertEquals(MODULE_VERSION, merged.modules().getFirst().moduleVersion());
        assertTrue(merged.modules().getFirst().payload().isObject());
    }
}
