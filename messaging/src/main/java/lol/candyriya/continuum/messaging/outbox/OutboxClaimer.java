package lol.candyriya.continuum.messaging.outbox;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The storage contract behind the outbox relay. Implemented by the storage
 * layer (SQL and MongoDB) in its own module — intentionally not implemented
 * here, so no fake or half-tested query shape ships in the transport.
 *
 * <p>Required semantics for implementations:</p>
 * <ul>
 * <li>{@link #claimBatch} is <b>atomic</b>: it must lock (or atomically
 * mark) the claimed rows so two relays never pick up the same row. SQL:
 * <pre>SELECT id, event_id, event_type, correlation_id, envelope, attempts, created_at
 *       FROM outbox_events
 *      WHERE published_at IS NULL
 *        AND (claimed_at IS NULL OR claimed_at &lt; :staleBefore)
 *   ORDER BY id
 *   LIMIT :limit
 *     FOR UPDATE SKIP LOCKED</pre>
 * inside one short transaction that sets {@code claimed_at = now()}, then
 * commits. MongoDB: an equivalent {@code findOneAndUpdate} with a
 * {@code claimed_at} marker and a filter that excludes fresh claims. Row
 * order must be stable (insertion order) so events drain in the order they
 * were committed.</li>
 * <li>Stale claims expire: a relay that dies between claim and
 * {@link #markPublished} must not wedge the row forever — implementations
 * reclaim rows whose {@code claimed_at} is older than a configurable
 * staleness bound.</li>
 * <li>Implementations should count claim attempts per row and stop claiming
 * (or set it aside) past a budget; a row that can never publish otherwise
 * spins in the relay forever.</li>
 * <li>{@link #markPublished} sets {@code published_at = now()} for the given
 * ids and is idempotent - re-marking an already published row is a no-op.
 * Only the relay calls it, and only after the broker confirmed the frame.</li>
 * <li>{@link #markFailed} records a failed publish attempt for the given
 * ids: increments {@code attempts}, stores the (short) reason, clears
 * {@code claimed_at} so the row is claimable again, and - past an
 * implementation-defined attempt budget - sets the row aside (terminal
 * state) instead of wedging the relay forever. Called by the relay when a
 * publish fails <i>and</i> the row is expected to be retried; it is the
 * counterpart of {@link #markPublished} for the failure path.</li>
 * <li>Never throw from {@code claimBatch} in a way that loses the batch;
 * on failure the relay logs and tries again on the next tick. On unrecoverable
 * failure, throw - the relay treats any throwable as "try again next tick".</li>
 * </ul>
 */
public interface OutboxClaimer {

    /**
     * Claims up to {@code limit} unpublished events, oldest first.
     *
     * @return the claimed batch; empty when the outbox has nothing pending.
     *         Never {@code null}.
     */
    List<OutboxEvent> claimBatch(int limit);

    /**
     * Marks the given events as published. Idempotent; called only after
     * broker confirmation of each frame.
     */
    void markPublished(Collection<UUID> eventIds);

    /**
     * Records a failed publish attempt for the given events. Implementations
     * should increment {@code attempts}, persist the reason (truncated to a
     * sane length), clear {@code claimed_at} so the rows become claimable
     * again, and set the row aside past their configured attempt budget so a
     * permanently unpublishable row stops cycling. Idempotency is not
     * required, but implementations must tolerate being called twice for the
     * same id (the relay can crash between the failure and this call - a
     * double {@code attempts} bump is harmless, a lost row is not).
     */
    void markFailed(Collection<UUID> eventIds, String reason);
}
