package lol.candyriya.continuum.common.protocol.payload;

import lol.candyriya.continuum.common.protocol.MessageTypeRegistry;
import lol.candyriya.continuum.common.protocol.ProtocolEvents;

/** Registry pre-loaded with the core flow's event types. Platforms extend it with their own. */
public final class CoreEventTypes {

    private CoreEventTypes() {
    }

    public static MessageTypeRegistry create() {
        return new MessageTypeRegistry()
                .register(ProtocolEvents.TRANSFER_REQUESTED, TransferRequestedPayload.class)
                .register(ProtocolEvents.TRANSFER_COMMITTED, TransferCommittedPayload.class)
                .register(ProtocolEvents.TRANSFER_FAILED, TransferFailedPayload.class)
                .register(ProtocolEvents.SESSION_RELEASED, SessionReleasedPayload.class);
    }
}
