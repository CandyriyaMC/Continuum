package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * A session was claimed on a backend (player landed and ownership was
 * established in the database). Other backends use this to notice the player
 * is taken before attempting a takeover.
 */
public record SessionCreatedPayload(UUID playerId,
                                    UUID sessionId,
                                    String backendId) implements EventPayload {
}
