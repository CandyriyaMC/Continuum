package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/** A snapshot was committed to the database. The database remains the source of truth; this is the heads-up. */
public record SnapshotUpdatedPayload(UUID playerId,
                                     String snapshotId,
                                     int schemaVersion) implements EventPayload {
}
