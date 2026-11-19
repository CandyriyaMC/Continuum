package lol.candyriya.continuum.common.protocol.payload;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * Transfer handoff initiated on the source backend. Carries the fencing token
 * the source held so the target can detect stale handoffs.
 */
public record TransferRequestedPayload(UUID playerId,
                                       String fromServerId,
                                       String toServerId,
                                       long fencingToken) implements EventPayload {
}
