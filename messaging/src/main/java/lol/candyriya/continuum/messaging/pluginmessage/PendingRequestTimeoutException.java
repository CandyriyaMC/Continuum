package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

import java.time.Duration;
import java.util.UUID;

/**
 * A pending request did not get its reply within the configured timeout.
 * The request is evicted from the table; the caller decides whether to retry
 * or give up. This is not an indication that the peer is gone.
 */
public final class PendingRequestTimeoutException extends PluginMessageException {

    private final UUID correlationId;
    private final Duration timeout;

    public PendingRequestTimeoutException(UUID correlationId, Duration timeout) {
        super(ErrorCode.MESSAGING_FAILED,
                "pending request " + correlationId + " timed out after " + timeout.toMillis() + " ms");
        this.correlationId = correlationId;
        this.timeout = timeout;
    }

    /** The correlation id that expired. */
    public UUID correlationId() {
        return correlationId;
    }

    /** The timeout that was exceeded. */
    public Duration timeout() {
        return timeout;
    }
}
