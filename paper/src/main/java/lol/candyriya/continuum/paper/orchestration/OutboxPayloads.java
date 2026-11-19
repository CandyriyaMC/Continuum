package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.SnapshotUpdatedPayload;
import lol.candyriya.continuum.storage.api.SaveOptions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Builds the outbox drafts riding the snapshot save transaction.
 *
 * <p>The draft is constructed before the write, so the history row id does not
 * exist yet at that point. {@code snapshot_id} therefore carries the fencing
 * token of the write instead: it is a stable, globally monotonic identifier,
 * and the history row records the same token, so a consumer that needs the
 * history row can look it up by token. When the storage API starts surfacing
 * the history id at draft time, this should switch to it.</p>
 */
final class OutboxPayloads {

    private static final JsonMapper MAPPER = JacksonMappers.create();

    private OutboxPayloads() {
    }

    static SaveOptions.OutboxDraft snapshotUpdated(UUID playerId, long fencingToken, int schemaVersion) {
        var payload = new SnapshotUpdatedPayload(playerId, Long.toString(fencingToken), schemaVersion);
        JsonNode node = MAPPER.valueToTree(payload);
        String payloadJson = node.toString();
        return new SaveOptions.OutboxDraft(BackboneEvents.SNAPSHOT_UPDATED, payloadJson);
    }
}
