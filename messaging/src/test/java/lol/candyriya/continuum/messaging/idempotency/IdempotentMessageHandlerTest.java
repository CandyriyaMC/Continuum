package lol.candyriya.continuum.messaging.idempotency;

import lol.candyriya.continuum.messaging.transport.HandlerVerdict;
import lol.candyriya.continuum.messaging.transport.InboundMessage;
import lol.candyriya.continuum.messaging.transport.TransportStats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.Protocol;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotentMessageHandlerTest {

    private static InboundMessage sample(UUID eventId) {
        MessageEnvelope<tools.jackson.databind.JsonNode> envelope =
                new MessageEnvelope<>(Protocol.CURRENT_VERSION, "continuum.session.created",
                        eventId, Instant.now(), "corr-" + eventId, JsonNodeFactory.instance.objectNode());
        return new InboundMessage(
                new lol.candyriya.continuum.messaging.transport.DeliveryMetadata(false, 1, 1,
                        "continuum.session.created", eventId.toString()),
                envelope);
    }

    private InMemoryProcessedEventStore store;
    private TransportStats stats;

    @BeforeEach
    void setUp() {
        store = new InMemoryProcessedEventStore();
        stats = new TransportStats();
    }

    @Test
    void duplicateDeliveryIsDiscardedBeforeTheDelegateRuns() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        IdempotentMessageHandler handler = new IdempotentMessageHandler("group", store,
                message -> {
                    delegateCalls.incrementAndGet();
                    return HandlerVerdict.ACK;
                }, stats);

        UUID eventId = UUID.randomUUID();
        assertEquals(HandlerVerdict.ACK, handler.handle(sample(eventId)));
        assertEquals(HandlerVerdict.ACK, handler.handle(sample(eventId)));
        assertEquals(HandlerVerdict.ACK, handler.handle(sample(eventId)));

        assertEquals(1, delegateCalls.get());
        assertEquals(2, stats.snapshot().duplicatesSkipped());
    }

    @Test
    void transientFailureIsNotMarkedAndSeesTheDelegateAgain() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        IdempotentMessageHandler handler = new IdempotentMessageHandler("group", store,
                message -> {
                    delegateCalls.incrementAndGet();
                    return delegateCalls.get() == 1 ? HandlerVerdict.NACK_REQUEUE : HandlerVerdict.ACK;
                });

        UUID eventId = UUID.randomUUID();
        assertEquals(HandlerVerdict.NACK_REQUEUE, handler.handle(sample(eventId)));
        // first attempt failed - the event must still be pending and visible to a retry
        assertFalse(store.isProcessed("group", eventId));
        assertEquals(HandlerVerdict.ACK, handler.handle(sample(eventId)));
        assertTrue(store.isProcessed("group", eventId));
        // third delivery is a duplicate
        assertEquals(HandlerVerdict.ACK, handler.handle(sample(eventId)));
        assertEquals(2, delegateCalls.get());
    }

    @Test
    void permanentFailureIsNotMarkedEither() throws Exception {
        IdempotentMessageHandler handler = new IdempotentMessageHandler("group", store,
                message -> HandlerVerdict.REJECT);

        UUID eventId = UUID.randomUUID();
        assertEquals(HandlerVerdict.REJECT, handler.handle(sample(eventId)));
        assertFalse(store.isProcessed("group", eventId));
    }

    @Test
    void delegateExceptionsPropagateUnchangedAndAreNotMarked() {
        RuntimeException boom = new RuntimeException("database is down");
        IdempotentMessageHandler handler = new IdempotentMessageHandler("group", store,
                message -> {
                    throw boom;
                });

        UUID eventId = UUID.randomUUID();
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> handler.handle(sample(eventId)));
        assertTrue(thrown == boom);
        assertFalse(store.isProcessed("group", eventId));
    }

    @Test
    void groupsDoNotShareMarkers() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        IdempotentMessageHandler groupA = new IdempotentMessageHandler("group-a", store,
                message -> {
                    delegateCalls.incrementAndGet();
                    return HandlerVerdict.ACK;
                });
        IdempotentMessageHandler groupB = new IdempotentMessageHandler("group-b", store,
                message -> {
                    delegateCalls.incrementAndGet();
                    return HandlerVerdict.ACK;
                });

        UUID eventId = UUID.randomUUID();
        groupA.handle(sample(eventId));
        groupB.handle(sample(eventId));
        assertEquals(2, delegateCalls.get());
    }

    @Test
    void constructorRejectsBlankGroup() {
        assertThrows(IllegalArgumentException.class,
                () -> new IdempotentMessageHandler("   ", store, message -> HandlerVerdict.ACK));
    }
}

