package lol.candyriya.continuum.storage.api;

/**
 * Called by {@link PlayerStorage#publishOutbox(int, int, OutboxPublisher)} for each claimed entry.
 *
 * <p>Implementations should treat {@link OutboxEntry#id()} as the idempotency
 * key when talking to the broker. Return {@code true} only when the broker has
 * confirmed the message (for example a publisher confirm); return {@code false}
 * — or throw — when the entry must be retried later. A throwing publisher does
 * not abort the batch: the entry is marked failed and the rest proceed.</p>
 */
@FunctionalInterface
public interface OutboxPublisher {

    /**
     * Publish one entry.
     *
     * @param entry the claimed entry; never null
     * @return true when the broker confirmed, false to schedule a retry
     * @throws Exception on an unrecoverable publish error; the entry is marked
     *         failed, the batch continues
     */
    boolean publish(OutboxEntry entry) throws Exception;
}
