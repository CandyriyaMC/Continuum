package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

import java.util.UUID;

/**
 * A correlation id is already in flight. UUID collisions are vanishingly
 * rare, so this means either a reused id (bug) or an attempt to shadow an
 * in-flight request (hostile). Either way: fail loudly, never overwrite the
 * existing pending entry.
 */
public final class DuplicateCorrelationIdException extends PluginMessageException {

    private final UUID correlationId;

    public DuplicateCorrelationIdException(UUID correlationId) {
        super(ErrorCode.MESSAGING_FAILED, "correlation id " + correlationId + " is already in flight");
        this.correlationId = correlationId;
    }

    /** The duplicate id. */
    public UUID correlationId() {
        return correlationId;
    }
}
