package lol.candyriya.continuum.messaging.transport;

/**
 * A subscription could not be established: queue/binding declaration failed,
 * the prefetch could not be negotiated, or the consumer could not be
 * registered. Thrown from {@code subscribe(...)} — never from delivery
 * callbacks.
 */
public final class SubscriptionException extends TransportException {

    public SubscriptionException(String message) {
        super(message);
    }

    public SubscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
