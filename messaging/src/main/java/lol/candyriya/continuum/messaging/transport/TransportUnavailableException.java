package lol.candyriya.continuum.messaging.transport;

/**
 * The transport is deliberately absent or unreachable: config disabled it,
 * the broker refused the connection at startup, or the selected mode requires
 * a transport that is not available. Publishing must never silently drop the
 * message — callers see this exception instead.
 */
public final class TransportUnavailableException extends TransportException {

    public TransportUnavailableException(String message) {
        super(message);
    }

    public TransportUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
