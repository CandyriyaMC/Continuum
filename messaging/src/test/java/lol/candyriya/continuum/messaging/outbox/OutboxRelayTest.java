package lol.candyriya.continuum.messaging.outbox;

import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.MessageTypeRegistry;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.SessionCreatedPayload;
import lol.candyriya.continuum.messaging.transport.MessageTransport;
import lol.candyriya.continuum.messaging.transport.PublishException;
import lol.candyriya.continuum.messaging.transport.Subscription;
import lol.candyriya.continuum.messaging.transport.SubscriptionException;
import lol.candyriya.continuum.messaging.transport.TransportStats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxRelayTest {

    private static final Duration POLL = Duration.ofMillis(50);

    private MessageTypeRegistry registry;
    private EnvelopeCodec codec;
    private InMemoryOutboxClaimer claimer;
    private FakeTransport transport;

    /** A transport that records publishes and lets the test decide how each one completes. */
    static final class FakeTransport implements MessageTransport {
        private volatile Function<MessageEnvelope<? extends EventPayload>, CompletionStage<Void>> behavior =
                envelope -> CompletableFuture.completedFuture(null);
        private final List<MessageEnvelope<? extends EventPayload>> published = new CopyOnWriteArrayList<>();
        private final TransportStats stats = new TransportStats();

        void behavior(Function<MessageEnvelope<? extends EventPayload>, CompletionStage<Void>> behavior) {
            this.behavior = behavior;
        }

        List<MessageEnvelope<? extends EventPayload>> published() {
            return List.copyOf(published);
        }

        @Override
        public CompletionStage<Void> publish(MessageEnvelope<? extends EventPayload> envelope) {
            published.add(envelope);
            return behavior.apply(envelope);
        }

        @Override
        public Subscription subscribe(String queueName, String bindingKey, lol.candyriya.continuum.messaging.transport.MessageHandler handler) {
            throw new SubscriptionException("fake transport cannot subscribe");
        }

        @Override
        public TransportStats stats() {
            return stats;
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }

    @BeforeEach
    void setUp() {
        registry = BackboneEvents.create();
        codec = new EnvelopeCodec(registry, 16_384);
        claimer = new InMemoryOutboxClaimer();
        transport = new FakeTransport();
    }

    private OutboxEvent event(String eventType) {
        UUID eventId = UUID.randomUUID();
        String correlation = "corr-" + eventId;
        SessionCreatedPayload payload = new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-1");
        MessageEnvelope<SessionCreatedPayload> envelope =
                new MessageEnvelope<>(lol.candyriya.continuum.common.protocol.Protocol.CURRENT_VERSION,
                        eventType, eventId, Instant.now(), correlation, payload);
        return new OutboxEvent(eventId, eventType, correlation, codec.encode(envelope), 0, Instant.now());
    }

    private static boolean await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    @Test
    void claimedEventsArePublishedAndMarkedAfterConfirm() throws Exception {
        claimer.add(event(BackboneEvents.SESSION_CREATED));
        claimer.add(event(BackboneEvents.SESSION_CREATED));

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.publishedEventIds().size() == 2, Duration.ofSeconds(5)),
                    "both events should be published and marked");
        }

        assertEquals(2, transport.published().size());
        assertTrue(claimer.failedEventIds().isEmpty());
        // rows are gone from pending - the relay does not double-publish on its own
        assertEquals(0, claimer.pendingCount());
    }

    @Test
    void publishFailureIsMarkedFailedNotPublished() throws Exception {
        claimer.add(event(BackboneEvents.SESSION_CREATED));
        transport.behavior(envelope -> CompletableFuture.failedFuture(new PublishException("broker said no")));

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.failedEventIds().size() == 1, Duration.ofSeconds(5)),
                    "failed publish should be marked failed");
        }

        assertTrue(claimer.publishedEventIds().isEmpty());
        assertEquals(1, claimer.failedEventIds().size());
    }

    @Test
    void unreadableEnvelopeIsMarkedFailedWithoutCallingTheTransport() throws Exception {
        UUID eventId = UUID.randomUUID();
        claimer.add(new OutboxEvent(eventId, BackboneEvents.SESSION_CREATED, "corr-x",
                new byte[]{1, 2, 3, 4}, 0, Instant.now()));

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.failedEventIds().contains(eventId), Duration.ofSeconds(5)),
                    "garbage frame should be marked failed");
        }

        assertTrue(transport.published().isEmpty());
        assertTrue(claimer.publishedEventIds().isEmpty());
    }

    @Test
    void unknownEventTypeIsMarkedFailed() throws Exception {
        // type has never been registered anywhere - the relay cannot bind it, so it must not retry forever
        UUID eventId = UUID.randomUUID();
        String type = "continuum.itest.neverregistered";
        SessionCreatedPayload payload = new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-1");
        MessageEnvelope<SessionCreatedPayload> envelope =
                new MessageEnvelope<>(lol.candyriya.continuum.common.protocol.Protocol.CURRENT_VERSION,
                        type, eventId, Instant.now(), "corr-y", payload);
        // signed with a registry that knows the type so the frame encodes, then handed to the relay
        MessageTypeRegistry donor = BackboneEvents.create().register(type, SessionCreatedPayload.class);
        byte[] frame = new EnvelopeCodec(donor, 16_384).encode(envelope);
        claimer.add(new OutboxEvent(eventId, type, "corr-y", frame, 0, Instant.now()));

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.failedEventIds().contains(eventId), Duration.ofSeconds(5)),
                    "unbindable frame should be marked failed");
        }

        assertTrue(transport.published().isEmpty());
    }

    @Test
    void aFailingClaimerDoesNotKillTheLoop() throws Exception {
        claimer.failClaims(new RuntimeException("database went away"));

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.claimCallCount() >= 2, Duration.ofSeconds(5)),
                    "relay should keep polling through claim failures");
            // database came back; the relay picks up where it left off
            claimer.failClaims(null);
            claimer.add(event(BackboneEvents.SESSION_CREATED));
            assertTrue(await(() -> claimer.publishedEventIds().size() == 1, Duration.ofSeconds(5)),
                    "relay should recover once the claimer recovers");
        }
    }

    @Test
    void publishedMarkWaitsForTheBrokerConfirm() throws Exception {
        claimer.add(event(BackboneEvents.SESSION_CREATED));
        CompletableFuture<Void> confirm = new CompletableFuture<>();
        transport.behavior(envelope -> confirm);

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> transport.published().size() == 1, Duration.ofSeconds(5)),
                    "relay should attempt the publish");
            Thread.sleep(500);
            assertTrue(claimer.publishedEventIds().isEmpty(),
                    "marking must not happen before the confirm");

            confirm.complete(null);
            assertTrue(await(() -> claimer.publishedEventIds().size() == 1, Duration.ofSeconds(5)),
                    "mark should follow the confirm");
        }
    }

    @Test
    void unconfirmedPublishNeverGetsMarked() throws Exception {
        claimer.add(event(BackboneEvents.SESSION_CREATED));
        transport.behavior(envelope -> new CompletableFuture<Void>());

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(POLL, 10, Duration.ofMillis(200)))) {
            relay.start();
            assertTrue(await(() -> transport.published().size() == 1, Duration.ofSeconds(5)));
            Thread.sleep(300);
            assertTrue(claimer.publishedEventIds().isEmpty());
            assertTrue(claimer.failedEventIds().isEmpty());
        }
    }

    @Test
    void closeStopsThePollLoop() throws Exception {
        OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(Duration.ofMillis(50), 10, Duration.ofSeconds(5)));
        relay.start();
        assertTrue(await(() -> claimer.claimCallCount() >= 1, Duration.ofSeconds(5)));
        relay.close();

        int callsAtClose = claimer.claimCallCount();
        Thread.sleep(400);
        assertEquals(callsAtClose, claimer.claimCallCount(), "poll loop must stop after close");
    }
}
