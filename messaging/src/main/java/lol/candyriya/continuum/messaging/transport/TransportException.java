package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

/**
 * Base for every transport failure this module raises.
 *
 * <p>Note: {@code common}'s {@code MessagingException} is final, so this
 * hierarchy cannot sit underneath it. It keeps the same machine-readable
 * {@link ErrorCode#MESSAGING_FAILED} code instead, so everything reachable
 * through messaging still matches {@code errorCode().code() == "messaging.failed"}.
 * If {@code MessagingException} is ever opened up for extension, re-root this
 * class under it and fold the codes together.</p>
 */
public sealed abstract class TransportException extends ContinuumException
        permits TransportUnavailableException, TransportClosedException, PublishException,
                SubscriptionException {

    protected TransportException(String message) {
        super(ErrorCode.MESSAGING_FAILED, message);
    }

    protected TransportException(String message, Throwable cause) {
        super(ErrorCode.MESSAGING_FAILED, message, cause);
    }
}
