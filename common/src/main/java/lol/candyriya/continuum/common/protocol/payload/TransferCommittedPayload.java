package lol.candyriya.continuum.common.protocol.payload;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * Transfer committed: the target confirmed ownership and the restore
 * succeeded. The source may now consider the session gone for good.
 */
public record TransferCommittedPayload(UUID playerId,
                                       UUID transferId,
                                       long fencingToken) implements EventPayload {
}
