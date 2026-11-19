package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.MessageTypeRegistry;
import lol.candyriya.continuum.common.protocol.ProtocolEvents;
import lol.candyriya.continuum.messaging.transport.TransportKeys;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backbone event ids are public contract - every node routes on them, so
 * they are pinned down here. If one changes, this test fails before the wire
 * does.
 */
class BackboneEventsTest {

    private final MessageTypeRegistry registry = BackboneEvents.create();
    private final EnvelopeCodec codec = new EnvelopeCodec(registry, 16_384);

    @Test
    void backboneIdsAreRegistered() {
        assertTrue(registry.isRegistered(BackboneEvents.SESSION_CREATED));
        assertTrue(registry.isRegistered(BackboneEvents.SESSION_TRANSFERRED));
        assertTrue(registry.isRegistered(BackboneEvents.SNAPSHOT_UPDATED));
        assertTrue(registry.isRegistered(BackboneEvents.BACKEND_HEALTH));
        assertTrue(registry.isRegistered(BackboneEvents.RECOVERY_REQUIRED));
        // core flow types ride along
        assertTrue(registry.isRegistered(ProtocolEvents.TRANSFER_REQUESTED));
        assertTrue(registry.isRegistered(ProtocolEvents.SESSION_RELEASED));
    }

    @Test
    void everyBackboneIdSatisfiesTheRoutingKeyGrammar() {
        for (String id : new String[] {BackboneEvents.SESSION_CREATED, BackboneEvents.SESSION_TRANSFERRED,
                BackboneEvents.SNAPSHOT_UPDATED, BackboneEvents.BACKEND_HEALTH, BackboneEvents.RECOVERY_REQUIRED}) {
            assertEquals(id, TransportKeys.requireValidBindingKey(id));
        }
    }

    @Test
    void sessionCreatedRoundTrips() {
        SessionCreatedPayload payload = new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-1");
        MessageEnvelope<SessionCreatedPayload> encoded =
                MessageEnvelope.create(BackboneEvents.SESSION_CREATED, "corr-1", payload);

        MessageEnvelope<SessionCreatedPayload> decoded =
                codec.decode(codec.encode(encoded), SessionCreatedPayload.class);

        assertEquals(payload, decoded.payload());
        assertEquals(encoded.eventId(), decoded.eventId());
        assertEquals(encoded.correlationId(), decoded.correlationId());
    }

    @Test
    void sessionTransferredRoundTrips() {
        SessionTransferredPayload payload = new SessionTransferredPayload(
                UUID.randomUUID(), UUID.randomUUID(), "backend-1", "backend-2", 7L);
        MessageEnvelope<SessionTransferredPayload> encoded =
                MessageEnvelope.create(BackboneEvents.SESSION_TRANSFERRED, "corr-2", payload);

        MessageEnvelope<SessionTransferredPayload> decoded =
                codec.decode(codec.encode(encoded), SessionTransferredPayload.class);

        assertEquals(payload, decoded.payload());
    }

    @Test
    void snapshotUpdatedRoundTrips() {
        SnapshotUpdatedPayload payload = new SnapshotUpdatedPayload(UUID.randomUUID(), "snap-17", 3);
        MessageEnvelope<SnapshotUpdatedPayload> encoded =
                MessageEnvelope.create(BackboneEvents.SNAPSHOT_UPDATED, "corr-3", payload);

        MessageEnvelope<SnapshotUpdatedPayload> decoded =
                codec.decode(codec.encode(encoded), SnapshotUpdatedPayload.class);

        assertEquals(payload, decoded.payload());
    }

    @Test
    void backendHealthRoundTrips() {
        BackendHealthPayload payload = new BackendHealthPayload("backend-1", HealthStatus.DEGRADED, 40, 100);
        MessageEnvelope<BackendHealthPayload> encoded =
                MessageEnvelope.create(BackboneEvents.BACKEND_HEALTH, "corr-4", payload);

        MessageEnvelope<BackendHealthPayload> decoded =
                codec.decode(codec.encode(encoded), BackendHealthPayload.class);

        assertEquals(payload, decoded.payload());
        assertEquals(HealthStatus.DEGRADED, decoded.payload().status());
    }

    @Test
    void recoveryRequiredRoundTrips() {
        RecoveryRequiredPayload payload = new RecoveryRequiredPayload(
                UUID.randomUUID(), UUID.randomUUID(), "backend-1", "interrupted transfer during handoff");
        MessageEnvelope<RecoveryRequiredPayload> encoded =
                MessageEnvelope.create(BackboneEvents.RECOVERY_REQUIRED, "corr-5", payload);

        MessageEnvelope<RecoveryRequiredPayload> decoded =
                codec.decode(codec.encode(encoded), RecoveryRequiredPayload.class);

        assertEquals(payload, decoded.payload());
    }
}
