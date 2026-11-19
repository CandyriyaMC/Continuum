package lol.candyriya.continuum.messaging.transport;

/**
 * What the broker-side decision table resolves to after a transient failure,
 * once the local retry budget is spent.
 */
public enum DeliveryDisposition {
    /** Hand the message back to the broker; it will be redelivered. */
    REQUEUE,
    /** Give up on this message; dead-letter it. */
    DEAD_LETTER
}
