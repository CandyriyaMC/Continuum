package lol.candyriya.continuum.storage.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of the transactional outbox: an event waiting to be (or already) published.
 *
 * @param id          database id, monotonic in commit order
 * @param msgType     event type discriminator for consumers, never null or blank
 * @param playerId    the player the event concerns, never null
 * @param payloadJson the event payload as a JSON string; never null
 * @param createdAt   when the row was written (UTC), never null
 * @param publishedAt when the publisher confirmed it (UTC), or null while unpublished
 * @param attempts    how many publish attempts have failed, {@code >= 0}
 * @param lastError   the last failure, truncated to 512 characters, or null
 */
public record OutboxEntry(long id,
                          String msgType,
                          UUID playerId,
                          String payloadJson,
                          Instant createdAt,
                          Instant publishedAt,
                          int attempts,
                          String lastError) {

    public OutboxEntry {
        if (id < 1) {
            throw new IllegalArgumentException("id must be >= 1, got " + id);
        }
        Objects.requireNonNull(msgType, "msgType must not be null");
        if (msgType.isBlank()) {
            throw new IllegalArgumentException("msgType must not be blank");
        }
        if (msgType.length() > 64) {
            throw new IllegalArgumentException("msgType must be at most 64 characters, got " + msgType.length());
        }
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0, got " + attempts);
        }
        if (lastError != null && lastError.length() > 512) {
            lastError = lastError.substring(0, 512);
        }
        // publishedAt, lastError may be null
    }

    /** True once the publisher has confirmed this entry. */
    public boolean published() {
        return publishedAt != null;
    }
}
