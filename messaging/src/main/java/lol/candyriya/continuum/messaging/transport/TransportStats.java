package lol.candyriya.continuum.messaging.transport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cheap live counters for a transport. Increments are lock-free and
 * contention-tolerant; readers get a consistent-ish cut via
 * {@link #snapshot()} (no cross-counter atomicity — these are metrics, not
 * accounting).
 */
public final class TransportStats {

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong returned = new AtomicLong();
    private final AtomicLong redelivered = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();
    private final AtomicLong unknownType = new AtomicLong();
    private final AtomicLong duplicatesSkipped = new AtomicLong();
    private final AtomicLong recoveries = new AtomicLong();

    /** A frame was handed to the broker (outbound attempt). */
    public void incrementPublished() {
        published.incrementAndGet();
    }

    /** The broker confirmed a publish. */
    public void incrementConfirmed() {
        confirmed.incrementAndGet();
    }

    /** A publish failed: encode error, channel failure, broker nack or confirm timeout. */
    public void incrementFailed() {
        failed.incrementAndGet();
    }

    /** A publish was returned by the broker as unroutable, even though mandatory was set. */
    public void incrementReturned() {
        returned.incrementAndGet();
    }

    /** A delivery arrived flagged as a redelivery. */
    public void incrementRedelivered() {
        redelivered.incrementAndGet();
    }

    /** A delivery was rejected to the dead-letter exchange (permanent failure or budget exhausted). */
    public void incrementDeadLettered() {
        deadLettered.incrementAndGet();
    }

    /** A delivery could not be decoded as a well-formed envelope; it was acked and dropped. */
    public void incrementMalformed() {
        malformed.incrementAndGet();
    }

    /** A delivery carried an event type this node does not know; it was acked and dropped. */
    public void incrementUnknownType() {
        unknownType.incrementAndGet();
    }

    /** A delivery was discarded by the idempotency guard before the delegate ran. */
    public void incrementDuplicatesSkipped() {
        duplicatesSkipped.incrementAndGet();
    }

    /** The underlying connection recovered after a failure. */
    public void incrementRecoveries() {
        recoveries.incrementAndGet();
    }

    public TransportStatsSnapshot snapshot() {
        return new TransportStatsSnapshot(published.get(), confirmed.get(), failed.get(), returned.get(),
                redelivered.get(), deadLettered.get(), malformed.get(), unknownType.get(),
                duplicatesSkipped.get(), recoveries.get());
    }
}
