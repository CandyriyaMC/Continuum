package lol.candyriya.continuum.messaging.rabbitmq;

import lol.candyriya.continuum.common.config.RabbitMqConfig;
import lol.candyriya.continuum.common.config.RabbitMqRetry;
import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.MessageTypeRegistry;
import lol.candyriya.continuum.common.protocol.Protocol;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.BackendHealthPayload;
import lol.candyriya.continuum.messaging.events.HealthStatus;
import lol.candyriya.continuum.messaging.events.SessionCreatedPayload;
import lol.candyriya.continuum.messaging.idempotency.IdempotentMessageHandler;
import lol.candyriya.continuum.messaging.idempotency.InMemoryProcessedEventStore;
import lol.candyriya.continuum.messaging.outbox.InMemoryOutboxClaimer;
import lol.candyriya.continuum.messaging.outbox.OutboxEvent;
import lol.candyriya.continuum.messaging.outbox.OutboxRelay;
import lol.candyriya.continuum.messaging.outbox.OutboxRelayConfig;
import lol.candyriya.continuum.messaging.transport.HandlerVerdict;
import lol.candyriya.continuum.messaging.transport.InboundMessage;
import lol.candyriya.continuum.messaging.transport.MessageHandler;
import lol.candyriya.continuum.messaging.transport.Subscription;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.ConnectionFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Broker-backed tests. They need a Docker daemon; without one every test here
 * skips cleanly via an assumption instead of failing, so laptops and CI share
 * the same command line. In CI (docker available) the full suite runs against
 * a real RabbitMQ 4.4.
 *
 * <p>All tests share the {@code continuum} vhost created at container start;
 * every test uses its own queue and its own event type namespace so they do
 * not step on each other, and the vhost is fresh per JVM run (the container
 * is fresh per run).</p>
 */
class RabbitMqIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqIntegrationTest.class);

    private static final String IMAGE = "rabbitmq:4.4-alpine";
    private static final String VHOST = "continuum";

    private static final RabbitMQContainer RABBIT;
    private static final boolean DOCKER_UP;

    static {
        RabbitMQContainer container = null;
        boolean up = false;
        try {
            if (DockerClientFactory.instance().isDockerAvailable()) {
                container = new RabbitMQContainer(DockerImageName.parse(IMAGE));
                container.start();
                // the transport lands on the continuum vhost by default - create it, mirroring production
                var add = container.execInContainer("rabbitmqctl", "add_vhost", VHOST);
                var perm = container.execInContainer("rabbitmqctl", "set_permissions", "-p", VHOST,
                        container.getAdminUsername(), ".*", ".*", ".*");
                up = add.getExitCode() == 0 && perm.getExitCode() == 0;
                if (!up) {
                    log.warn("failed to create the {} vhost in the container; skipping broker tests", VHOST);
                    container.stop();
                }
            } else {
                log.warn("docker is not available - broker integration tests will be skipped");
            }
        } catch (Throwable t) {
            log.warn("rabbitmq container failed to start - broker integration tests will be skipped: {}", t.toString());
            if (container != null) {
                try {
                    container.stop();
                } catch (Throwable ignored) {
                    // nothing useful to do with a container that failed to boot
                }
            }
            container = null;
        }
        RABBIT = container;
        DOCKER_UP = up;
    }

    @AfterAll
    static void stopBroker() {
        if (RABBIT != null) {
            RABBIT.stop();
        }
    }

    @BeforeEach
    void requireDocker() {
        assumeTrue(DOCKER_UP, "docker is not available - skipping broker-backed test");
    }

    // ----- fixtures ---------------------------------------------------------------

    private static String unique(String prefix) {
        return "w3it-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static RabbitMqConfig config(String uri) {
        return new RabbitMqConfig(true, uri, "continuum.events",
                new RabbitMqRetry(5, Duration.ofMillis(50), Duration.ofSeconds(1)));
    }

    private EnvelopeCodec codecFor(String... eventTypes) {
        MessageTypeRegistry registry = BackboneEvents.create();
        for (String type : eventTypes) {
            if (!registry.isRegistered(type)) {
                registry.register(type, SessionCreatedPayload.class);
            }
        }
        return new EnvelopeCodec(registry, 16_384);
    }

    private RabbitMqTransport newTransport(EnvelopeCodec codec) {
        // short heartbeat so a dead broker is noticed quickly, even in tests
        RabbitMqTransportOptions options = new RabbitMqTransportOptions(
                25, Duration.ofSeconds(5), 3, 5, 100_000, 10_000,
                "continuum-it", Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        return new RabbitMqTransport(config(RABBIT.getAmqpUrl()), codec, options);
    }

    /** Raw channel against the continuum vhost for driving broker state directly; owns its connection. */
    private RawBroker rawBroker() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        factory.setVirtualHost(VHOST);
        com.rabbitmq.client.Connection connection =
                factory.newConnection("continuum-it-raw-" + UUID.randomUUID().toString().substring(0, 8));
        return new RawBroker(connection, connection.createChannel());
    }

    /** A raw channel plus the connection that owns it - both close together, nothing leaks. */
    private record RawBroker(com.rabbitmq.client.Connection connection, com.rabbitmq.client.Channel channel)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                channel.close();
            } finally {
                connection.close();
            }
        }
    }

    private static AMQP.BasicProperties jsonProperties(String messageId, String correlationId) {
        return new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .messageId(messageId)
                .correlationId(correlationId)
                .build();
    }

    private static byte[] rawEnvelope(int protocolVersion, String eventType, UUID eventId) {
        String json = "{\"protocol_version\":" + protocolVersion
                + ",\"event_type\":\"" + eventType
                + "\",\"event_id\":\"" + eventId
                + "\",\"timestamp\":" + Instant.now().toEpochMilli()
                + ",\"correlation_id\":\"corr-raw\""
                + ",\"payload\":{\"player_id\":\"" + UUID.randomUUID()
                + "\",\"session_id\":\"" + UUID.randomUUID()
                + "\",\"backend_id\":\"backend-it\"}}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean await(java.util.function.BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private static MessageEnvelope<SessionCreatedPayload> sessionCreated(String type) {
        return MessageEnvelope.create(type, "corr-" + UUID.randomUUID(),
                new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-it"));
    }

    // ----- tests ---------------------------------------------------------------

    @Test
    void publishConfirmConsumeRoundtrip() throws Exception {
        String type = "continuum.it.roundtrip";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransport transport = newTransport(codec);
        try {
            CountDownLatch received = new CountDownLatch(1);
            InboundMessage[] holder = new InboundMessage[1];
            Subscription subscription = transport.subscribe(unique("roundtrip"), type, message -> {
                holder[0] = message;
                received.countDown();
                return HandlerVerdict.ACK;
            });

            MessageEnvelope<SessionCreatedPayload> envelope = sessionCreated(type);
            transport.publish(envelope).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertTrue(received.await(10, TimeUnit.SECONDS), "delivery should arrive");
            MessageEnvelope<?> inbound = holder[0].envelope();
            assertEquals(envelope.eventId(), inbound.eventId());
            assertEquals(type, inbound.eventType());
            assertEquals(envelope.correlationId(), inbound.correlationId());
            assertEquals(type, holder[0].metadata().routingKey());
            assertEquals(envelope.eventId().toString(), holder[0].metadata().messageId());
            assertTrue(transport.stats().snapshot().confirmed() >= 1);
            subscription.close().toCompletableFuture().join();
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void unroutableMandatoryPublishLandsInAlternateExchange() throws Exception {
        String type = "continuum.it.unroutable";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransport transport = newTransport(codec);
        // note: no subscription on purpose - nothing binds the events exchange to this type
        try {
            MessageEnvelope<SessionCreatedPayload> envelope = sessionCreated(type);
            transport.publish(envelope).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            GetResponse response = null;
            try (RawBroker raw = rawBroker()) {
                com.rabbitmq.client.Channel channel = raw.channel();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (System.nanoTime() < deadline && response == null) {
                    response = channel.basicGet(Topology.AE_QUEUE, true);
                    if (response == null) {
                        Thread.sleep(100);
                    }
                }
            }
            assertNotNull(response, "unroutable mandatory publish should land in the alternate exchange queue");
            String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("continuum.it.unroutable"), body);
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void poisonMessageDeadLettersAfterTheDeliveryLimit() throws Exception {
        String type = "continuum.it.poison";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransportOptions options = new RabbitMqTransportOptions(
                5, Duration.ofSeconds(5), 1, 5, 100_000, 10_000,
                "continuum-it-poison", Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        RabbitMqTransport transport = new RabbitMqTransport(config(RABBIT.getAmqpUrl()), codec, options);
        try {
            transport.subscribe(unique("poison"), type, message -> HandlerVerdict.NACK_REQUEUE);

            MessageEnvelope<SessionCreatedPayload> envelope = sessionCreated(type);
            transport.publish(envelope).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            GetResponse deadLettered = null;
            try (RawBroker raw = rawBroker()) {
                com.rabbitmq.client.Channel channel = raw.channel();
                long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                while (System.nanoTime() < deadline && deadLettered == null) {
                    deadLettered = channel.basicGet(Topology.DLQ_QUEUE, true);
                    if (deadLettered == null) {
                        Thread.sleep(200);
                    }
                }
            }
            assertNotNull(deadLettered, "poison message should end up in the dead-letter queue");
            String body = new String(deadLettered.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("continuum.it.poison"), body);
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void unacknowledgedMessageIsRedeliveredAfterConsumerCrash() throws Exception {
        String type = "continuum.it.crash";
        EnvelopeCodec codec = codecFor(type);
        String queue = unique("crash");
        RabbitMqTransport transport = newTransport(codec);
        try {
            CountDownLatch enteredFirst = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            Subscription first = transport.subscribe(queue, type, message -> {
                enteredFirst.countDown();
                // simulate a handler stuck in flight when the consumer dies - never acks in time
                releaseFirst.await(10, TimeUnit.SECONDS);
                return HandlerVerdict.ACK;
            });

            MessageEnvelope<SessionCreatedPayload> envelope = sessionCreated(type);
            transport.publish(envelope).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertTrue(enteredFirst.await(10, TimeUnit.SECONDS), "first consumer should pick the message up");

            // cancel the consumer without acking - the broker requeues the unacked message
            first.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            releaseFirst.countDown();

            // same queue, brand-new consumer - this is the crash-redelivery shape
            CountDownLatch redeliveredLatch = new CountDownLatch(1);
            InboundMessage[] second = new InboundMessage[1];
            transport.subscribe(queue, type, message -> {
                second[0] = message;
                redeliveredLatch.countDown();
                return HandlerVerdict.ACK;
            });

            assertTrue(redeliveredLatch.await(15, TimeUnit.SECONDS),
                    "requeued message should arrive on the fresh consumer");
            assertTrue(second[0].metadata().redelivered(), "broker must flag the redelivery");
            assertEquals(envelope.eventId(), second[0].envelope().eventId());
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void idempotentConsumerDiscardsDuplicateDelivery() throws Exception {
        String type = "continuum.it.idempotent";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransport transport = newTransport(codec);
        try {
            InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
            AtomicInteger delegateCalls = new AtomicInteger();
            MessageHandler delegate = message -> {
                delegateCalls.incrementAndGet();
                return HandlerVerdict.ACK;
            };
            MessageHandler idempotent = new IdempotentMessageHandler(
                    "it-group", store, delegate, transport.stats());

            transport.subscribe(unique("idem"), type, idempotent);

            UUID eventId = UUID.randomUUID();
            SessionCreatedPayload payload = new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-it");
            // two frames, SAME event id - the classic at-least-once duplicate
            MessageEnvelope<SessionCreatedPayload> first = new MessageEnvelope<>(
                    Protocol.CURRENT_VERSION, type, eventId, Instant.now(), "corr-dup", payload);
            MessageEnvelope<SessionCreatedPayload> second = new MessageEnvelope<>(
                    Protocol.CURRENT_VERSION, type, eventId, Instant.now(), "corr-dup", payload);
            transport.publish(first).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            transport.publish(second).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertTrue(await(() -> transport.stats().snapshot().duplicatesSkipped() >= 1, Duration.ofSeconds(10)),
                    "duplicate delivery should be discarded");
            assertEquals(1, delegateCalls.get());
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void outboxRelayClaimsPublishesAndMarksOnlyAfterConfirm() throws Exception {
        String type = BackboneEvents.SESSION_CREATED;
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransport transport = newTransport(codec);
        InMemoryOutboxClaimer claimer = new InMemoryOutboxClaimer();

        CountDownLatch delivered = new CountDownLatch(2);
        transport.subscribe(unique("outbox"), "continuum.session.#", message -> {
            delivered.countDown();
            return HandlerVerdict.ACK;
        });

        for (int i = 0; i < 2; i++) {
            UUID eventId = UUID.randomUUID();
            String correlation = "corr-relay-" + eventId;
            MessageEnvelope<SessionCreatedPayload> envelope = new MessageEnvelope<>(
                    Protocol.CURRENT_VERSION, type, eventId, Instant.now(), correlation,
                    new SessionCreatedPayload(UUID.randomUUID(), UUID.randomUUID(), "backend-it"));
            claimer.add(new OutboxEvent(eventId, type, correlation, codec.encode(envelope), 0, Instant.now()));
        }

        try (OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                new OutboxRelayConfig(Duration.ofMillis(100), 10, Duration.ofSeconds(5)))) {
            relay.start();
            assertTrue(await(() -> claimer.publishedEventIds().size() == 2, Duration.ofSeconds(15)),
                    "relay should claim, publish and mark both events");
            assertTrue(delivered.await(10, TimeUnit.SECONDS), "both events should reach the subscriber");
            assertTrue(claimer.failedEventIds().isEmpty());
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void unsupportedProtocolVersionIsLoggedAckedAndDropped() throws Exception {
        String type = "continuum.it.version";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransport transport = newTransport(codec);
        try {
            AtomicInteger deliveries = new AtomicInteger();
            transport.subscribe(unique("version"), type, message -> {
                deliveries.incrementAndGet();
                return HandlerVerdict.ACK;
            });

            try (RawBroker raw = rawBroker()) {
                com.rabbitmq.client.Channel channel = raw.channel();
                channel.basicPublish("continuum.events", type, true,
                        jsonProperties(UUID.randomUUID().toString(), "corr-version"),
                        rawEnvelope(99, type, UUID.randomUUID()));
            }
            Thread.sleep(2500);

            assertEquals(0, deliveries.get(), "unsupported versions must not reach the handler");
            assertTrue(transport.stats().snapshot().malformed() >= 1);
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void unknownEventTypeIsLoggedAckedAndDropped() throws Exception {
        EnvelopeCodec codec = codecFor("continuum.it.known");
        RabbitMqTransport transport = newTransport(codec);
        try {
            AtomicInteger deliveries = new AtomicInteger();
            transport.subscribe(unique("unknown"), "continuum.it.#", message -> {
                deliveries.incrementAndGet();
                return HandlerVerdict.ACK;
            });

            try (RawBroker raw = rawBroker()) {
                com.rabbitmq.client.Channel channel = raw.channel();
                channel.basicPublish("continuum.events", "continuum.it.unknown-type", true,
                        jsonProperties(UUID.randomUUID().toString(), "corr-unknown"),
                        rawEnvelope(Protocol.CURRENT_VERSION, "continuum.it.unknown-type", UUID.randomUUID()));
            }
            Thread.sleep(2500);

            assertEquals(0, deliveries.get(), "unknown types must not reach the handler");
            assertTrue(transport.stats().snapshot().unknownType() >= 1);
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }

    @Test
    void redeliveredFlagIsExposedOnRedelivery() throws Exception {
        String type = "continuum.it.redelivered";
        EnvelopeCodec codec = codecFor(type);
        RabbitMqTransportOptions options = new RabbitMqTransportOptions(
                5, Duration.ofSeconds(5), 1, 5, 100_000, 10_000,
                "continuum-it-redeliver", Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(1));
        RabbitMqTransport transport = new RabbitMqTransport(config(RABBIT.getAmqpUrl()), codec, options);
        try {
            ConcurrentLinkedQueue<Boolean> redeliveries = new ConcurrentLinkedQueue<>();
            CountDownLatch seen = new CountDownLatch(2);
            transport.subscribe(unique("redeliver"), type, message -> {
                redeliveries.add(message.metadata().redelivered());
                seen.countDown();
                if (redeliveries.size() == 1) {
                    return HandlerVerdict.NACK_REQUEUE; // transient - broker redelivers
                }
                return HandlerVerdict.ACK;
            });

            MessageEnvelope<SessionCreatedPayload> envelope = sessionCreated(type);
            transport.publish(envelope).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertTrue(seen.await(15, TimeUnit.SECONDS), "message should be delivered twice");
            assertEquals(List.of(false, true), List.copyOf(redeliveries));
            assertTrue(transport.stats().snapshot().redelivered() >= 1);
        } finally {
            transport.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }
}
