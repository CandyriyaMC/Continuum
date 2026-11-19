package lol.candyriya.continuum.messaging.transport;

/**
 * Thrown when an operation is attempted on a transport (or subscription) that
 * has already been closed. Always a caller bug — do not retry against the
 * same instance, build a fresh one.
 */
public final class TransportClosedException extends TransportException {

    public TransportClosedException(String message) {
        super(message);
    }
}
