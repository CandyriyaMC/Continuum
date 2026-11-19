package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * Session ownership moved from one backend to another and the handoff
 * committed. Carries the fencing token that was valid at commit time so late
 * messages from the previous owner can be identified as stale.
 */
public record SessionTransferredPayload(UUID playerId,
                                        UUID sessionId,
                                        String fromServerId,
                                        String toServerId,
                                        long fencingToken) implements EventPayload {
}
