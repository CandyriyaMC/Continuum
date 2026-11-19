package lol.candyriya.continuum.common.protocol.payload;

import lol.candyriya.continuum.common.protocol.EventPayload;

import java.util.UUID;

/**
 * Transfer aborted before commit. {@code reasonCode} is a stable machine code
 * (e.g. {@code snapshot-too-large}, {@code fencing-conflict}), not user text —
 * platforms map it to a localized message.
 */
public record TransferFailedPayload(UUID playerId,
                                    UUID transferId,
                                    String reasonCode) implements EventPayload {
}
