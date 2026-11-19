package lol.candyriya.continuum.common.error;

/**
 * Messaging-transport failures (publish refused, channel closed, retry budget
 * exhausted). Instantiated by the messaging modules.
 */
public final class MessagingException extends ContinuumException {

    public MessagingException(String message) {
        super(ErrorCode.MESSAGING_FAILED, message);
    }

    public MessagingException(String message, Throwable cause) {
        super(ErrorCode.MESSAGING_FAILED, message, cause);
    }
}
