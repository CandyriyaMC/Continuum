package lol.candyriya.continuum.common.protocol.payload;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/** Session ownership was released: clean disconnect, takeover by a newer session, or transfer commit. */
public record SessionReleasedPayload(UUID playerId,
                                     String serverId,
                                     long fencingToken) implements EventPayload {
}
