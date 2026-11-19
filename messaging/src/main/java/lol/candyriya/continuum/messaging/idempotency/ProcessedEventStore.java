package lol.candyriya.continuum.messaging.idempotency;

import java.util.UUID;

/**
 * The storage contract behind idempotent consumers.
 *
 * <p><b>Durable implementations must</b> (this is what the storage layer wires up):</p>
 * <ul>
 * <li>enforce a unique constraint on {@code (consumer_group, event_id)} — a plain
 * {@code INSERT ... ON CONFLICT DO NOTHING} is the shape of {@link #markProcessed}</li>
 * <li>for handlers whose side effects are database writes, run the mark inside the
 * <i>same transaction</i> as the business write, so the state and the "seen" marker
 * commit or roll back together — that is what turns at-least-once delivery into
 * effectively-once processing</li>
 * <li>treat {@link #isProcessed} as an optimization, never as the correctness
 * mechanism; the unique index is the correctness mechanism</li>
 * </ul>
 *
 * <p>The in-memory implementation shipped here is a reference for tests and
 * development only. It is unbounded and volatile — do not run production
 * consumers on it.</p>
 */
public interface ProcessedEventStore {

    /**
     * @return true when this consumer group already recorded {@code eventId};
     *         false otherwise. Implementations should make this cheap
     *         (unique-index probe), not a table scan.
     */
    boolean isProcessed(String consumerGroup, UUID eventId);

    /**
     * Records {@code eventId} as processed for the group.
     *
     * @return true when this call did the recording, false when the id was
     *         already present (duplicate delivery)
     */
    boolean markProcessed(String consumerGroup, UUID eventId);
}
