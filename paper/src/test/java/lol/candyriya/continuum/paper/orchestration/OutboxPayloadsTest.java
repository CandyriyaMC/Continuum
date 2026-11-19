package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.SnapshotUpdatedPayload;
import lol.candyriya.continuum.storage.api.SaveOptions;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxPayloadsTest {

    @Test
    void draftUsesTheSnapshotUpdatedTypeAndTheTokenAsSnapshotId() {
        UUID playerId = UUID.randomUUID();

        SaveOptions.OutboxDraft draft = OutboxPayloads.snapshotUpdated(playerId, 4242, 1);

        assertEquals(BackboneEvents.SNAPSHOT_UPDATED, draft.msgType());
        JsonMapper mapper = JacksonMappers.create();
        SnapshotUpdatedPayload decoded = mapper.readValue(draft.payloadJson(), SnapshotUpdatedPayload.class);
        assertEquals(playerId, decoded.playerId());
        assertEquals("4242", decoded.snapshotId());
        assertEquals(1, decoded.schemaVersion());
    }

    @Test
    void payloadKeysAreSnakeCaseOnTheWire() {
        SaveOptions.OutboxDraft draft = OutboxPayloads.snapshotUpdated(UUID.randomUUID(), 7, 3);

        assertTrue(draft.payloadJson().contains("\"player_id\""));
        assertTrue(draft.payloadJson().contains("\"snapshot_id\""));
        assertTrue(draft.payloadJson().contains("\"schema_version\""));
    }
}
