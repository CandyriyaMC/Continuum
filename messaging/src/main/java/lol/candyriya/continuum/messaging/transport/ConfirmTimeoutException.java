package lol.candyriya.continuum.messaging.transport;

/**
 * The broker did not confirm the publish inside the confirmation budget.
 * The message may or may not have been routed — at-least-once delivery means
 * the outbox will republish it and idempotent consumers absorb the duplicate.
 */
public final class ConfirmTimeoutException extends PublishException {

    public ConfirmTimeoutException(String message) {
        super(message);
    }
}
