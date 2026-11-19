package lol.candyriya.continuum.messaging.transport;

/**
 * The retry decision table, kept pure so it can be unit-tested to death.
 *
 * <p>Budget semantics: the budget tracks how many times the broker has
 * <i>tried</i> this message, not how many times we feel like retrying in
 * process. Once the effective count reaches {@code maxAttempts} the message
 * is dead-lettered instead of requeued - a poison message must not circle
 * forever.</p>
 *
 * <p>Which header counts, on a modern broker:</p>
 * <ul>
 * <li>{@code x-delivery-count} - failed deliveries. AMQP 0.9.1 {@code
 * basic.reject(tag, false)} and consumer crashes increment it; {@code
 * basic.nack} does <b>not</b> (since RabbitMQ 4.3 the delivery limit is
 * evaluated against delivery-count, and nack-based requeues are explicitly
 * exempt so applications can return messages without burning the budget).</li>
 * <li>{@code x-acquired-count} - deliveries assigned to any consumer. This
 * <b>does</b> increment on {@code basic.nack(..., requeue=true)}, which is
 * why the table takes the maximum of the two: anything less would loop
 * forever on a deterministic handler failure.</li>
 * </ul>
 */
public final class RetryDecider {

    private RetryDecider() {
    }

    /**
     * Decides the fate of a message whose handler just reported a transient
     * failure and whose local retry budget is exhausted.
     */
    public static DeliveryDisposition afterTransientFailure(int deliveryCount, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        return deliveryCount >= maxAttempts ? DeliveryDisposition.DEAD_LETTER : DeliveryDisposition.REQUEUE;
    }

    /**
     * Best-effort delivery count on a quorum queue: the maximum of
     * {@code x-delivery-count} (failed deliveries, reject-counted) and
     * {@code x-acquired-count} (assignments, nack-counted). A classic queue
     * reports neither - a redelivered flag then means "we know this is not
     * the first time", so the safe call is to treat the budget as exhausted;
     * a first-time delivery counts as 1. Pass {@code -1} for headers the
     * broker did not send.
     */
    public static int effectiveDeliveryCount(int deliveryCountHeader, int acquiredCountHeader,
                                             boolean redelivered, int maxAttempts) {
        int best = Math.max(deliveryCountHeader, acquiredCountHeader);
        if (best >= 0) {
            return best;
        }
        return redelivered ? Math.max(1, maxAttempts) : 1;
    }
}
