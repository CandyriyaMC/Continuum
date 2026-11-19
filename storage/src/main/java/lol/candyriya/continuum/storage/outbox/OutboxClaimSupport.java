package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.messaging.outbox.OutboxEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Small shared helpers for the engine claimers. Not public API — the claimers
 * are the API.
 */
final class OutboxClaimSupport {

    /** How long a claimed-but-unpublished row stays claimed before a peer reclaims it. */
    static final long CLAIM_STALE_SECONDS = 30;

    /** Attempt budget applied by the relay claimers when the caller has no opinion. */
    static final int DEFAULT_MAX_ATTEMPTS = 10;

    private OutboxClaimSupport() {
    }

    /**
     * Deterministic relay-internal handle for a storage outbox row. The outbox
     * has a monotonic BIGINT id, not a UUID, so the relay's {@code eventId} is
     * a name-based UUID of that id; consumers never see it (they dedupe on the
     * envelope's own {@code eventId} inside the payload), it exists so the
     * relay can correlate {@code markPublished}/{@code markFailed} calls back
     * to rows.
     */
    static UUID eventIdFor(long rowId) {
        return UUID.nameUUIDFromBytes(("continuum-outbox-" + rowId).getBytes(StandardCharsets.UTF_8));
    }

    /** A relay event view of one storage outbox row. */
    static OutboxEvent toEvent(long id, String msgType, String playerUuid, String payloadJson,
                               int attempts, Instant createdAt) {
        byte[] envelope = payloadJson == null ? new byte[0] : payloadJson.getBytes(StandardCharsets.UTF_8);
        if (envelope.length == 0) {
            // an empty frame can never re-bind to a payload type anyway; give the
            // relay a parseable object so it fails through the normal decode path
            // (attempts bump, eventual set-aside) instead of wedging the batch
            envelope = "{}".getBytes(StandardCharsets.UTF_8);
        }
        return new OutboxEvent(eventIdFor(id), msgType, playerUuid, envelope, attempts, createdAt);
    }

    static String truncate(String error) {
        String message = error == null || error.isBlank() ? "unknown publisher error" : error;
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
