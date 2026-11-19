package lol.candyriya.continuum.messaging.transport;

/**
 * The explicit decision a {@link MessageHandler} returns for one delivery.
 * The handler owns the decision; the transport owns the wire mechanics.
 *
 * <ul>
 * <li>{@link #ACK} — processed, or deliberately dropped (unknown type,
 * unreadable frame). The message is acknowledged and never comes back.</li>
 * <li>{@link #NACK_REQUEUE} - transient failure (database down, downstream
 * unavailable). The transport retries locally with bounded backoff; when the
 * local budget is spent it hands back to the broker decision table
 * (see {@link RetryDecider}): requeue while the delivery budget
 * ({@code x-delivery-count} / {@code x-acquired-count}) lasts, dead-letter
 * once it is exhausted.</li>
 * <li>{@link #REJECT} — permanent failure (payload we will never be able to
 * process). Straight to the dead-letter exchange, no requeue.</li>
 * </ul>
 */
public enum HandlerVerdict {
    ACK,
    NACK_REQUEUE,
    REJECT
}
