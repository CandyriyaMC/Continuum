package lol.candyriya.continuum.messaging.transport;

/**
 * A publish did not make it out: the channel rejected the frame, the broker
 * negatively confirmed it, confirmation timed out, or the frame could not be
 * encoded. The future returned by {@code publish(...)} completes exceptionally
 * with this exception; outbox relays rely on that to leave the row unpublished
 * so it gets retried.
 */
public sealed class PublishException extends TransportException
        permits ConfirmTimeoutException {

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
