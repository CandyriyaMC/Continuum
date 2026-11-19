package lol.candyriya.continuum.messaging.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.RecoverableConnection;
import com.rabbitmq.client.Recoverable;

import lol.candyriya.continuum.common.config.RabbitMqConfig;
import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.messaging.transport.DeliveryDisposition;
import lol.candyriya.continuum.messaging.transport.DeliveryMetadata;
import lol.candyriya.continuum.messaging.transport.HandlerVerdict;
import lol.candyriya.continuum.messaging.transport.InboundMessage;
import lol.candyriya.continuum.messaging.transport.MessageHandler;
import lol.candyriya.continuum.messaging.transport.MessageTransport;
import lol.candyriya.continuum.messaging.transport.RetryDecider;
import lol.candyriya.continuum.messaging.transport.Subscription;
import lol.candyriya.continuum.messaging.transport.SubscriptionException;
import lol.candyriya.continuum.messaging.transport.TransportClosedException;
import lol.candyriya.continuum.messaging.transport.TransportKeys;
import lol.candyriya.continuum.messaging.transport.TransportStats;
import lol.candyriya.continuum.messaging.transport.TransportUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * The RabbitMQ-backed transport: one long-lived connection per RabbitMQ
 * config, a confirm-tracked publisher channel, and one channel per
 * subscription with broker-side delivery accounting.
 *
 * <p>Topology is declared at construction (see {@link Topology}) - the broker
 * needs no {@code rabbitmqctl} and no policies; arguments carry the wiring.
 * The events exchange is bound to an alternate exchange so mandatory publishes
 * never vanish silently; worker queues are quorum with a delivery limit as a
 * broker-side backstop to the consumer-side retry decision table.</p>
 *
 * <p>Connection failure model:</p>
 * <ul>
 * <li>startup against an unreachable or refused broker fails fast with
 * {@link TransportUnavailableException} - a node that was promised a broker
 * and cannot get one has nothing useful to do</li>
 * <li>mid-flight failures lean on the client's automatic topology/consumer
 * recovery (5.x); outstanding publishes are failed eagerly by the publisher
 * channel's shutdown listener, never silently hung; the outbox republishes</li>
 * <li>heartbeat is 10s by default so a half-open connection is detected
 * within seconds, not minutes</li>
 * </ul>
 *
 * <p>Consumer decisions: malformed frames (the codec's structural validation
 * rejects broken payloads, oversized frames and unsupported protocol
 * versions), and unknown event types are logged, counted and acked - never
 * requeued. Handler verdicts and handler exceptions are routed through the
 * same bounded retry table (see {@link RetryDecider}); after the broker-side
 * delivery budget the message is rejected to the dead-letter exchange instead
 * of circling.</p>
 *
 * <p>Close order is subscriptions (basicCancel, requeue) → publisher channel
 * → connection. Unacknowledged messages are requeued by the broker on every
 * leg. Idempotent.</p>
 */
public final class RabbitMqTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqTransport.class);

    /** The vhost used when the URI carries none; never logged as part of credentials. */
    static final String DEFAULT_VHOST = "continuum";

    private static final int CLOSE_TIMEOUT_MILLIS = 5_000;

    /** Lazily created, lives for the JVM; shutdown is a one-shot path, not a pool. */
    private static final java.util.concurrent.ExecutorService CLOSE_EXECUTOR =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final RabbitMqConfig config;
    private final EnvelopeCodec codec;
    private final RabbitMqTransportOptions options;
    private final TransportStats stats = new TransportStats();
    private final Set<RabbitMqSubscription> subscriptions = ConcurrentHashMap.newKeySet();

    private final Connection connection;
    private final Publisher publisher;

    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Builds the transport and its connection. Callers that want the
     * degraded path instead use {@code TransportSelection} with the config
     * disabled - constructing this on a disabled config is a caller bug and
     * fails loudly.
     *
     * @throws TransportUnavailableException when the config is disabled, the
     *         URI is unusable, the broker refuses the connection, or the
     *         topology cannot be declared
     */
    public RabbitMqTransport(RabbitMqConfig config, EnvelopeCodec codec,
                             RabbitMqTransportOptions options) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");

        if (!config.enabled()) {
            throw new TransportUnavailableException(
                    "rabbitmq transport is disabled by configuration - use TransportSelection to get the degraded path");
        }
        if (config.exchange() == null || config.exchange().isBlank()) {
            throw new TransportUnavailableException("rabbitmq exchange name must not be blank");
        }

        ConnectionFactory factory = buildConnectionFactory(config.uri(), options);
        Connection opened;
        try {
            opened = factory.newConnection();
        } catch (IOException | TimeoutException e) {
            throw new TransportUnavailableException(
                    "cannot reach the rabbitmq broker - failing fast instead of dropping events silently", e);
        } catch (RuntimeException e) {
            throw new TransportUnavailableException("rabbitmq connection setup blew up", e);
        }
        this.connection = opened;
        if (this.connection instanceof RecoverableConnection recoverable) {
            recoverable.addRecoveryListener(new RecoveryListener() {
                @Override
                public void handleRecoveryStarted(Recoverable conn) {
                    log.warn("rabbitmq connection lost - recovery started");
                }

                @Override
                public void handleRecovery(Recoverable conn) {
                    stats.incrementRecoveries();
                    log.info("rabbitmq connection recovered; topology and consumers are re-declared by the client");
                }
            });
        }
        this.connection.addShutdownListener(signal -> {
            if (!signal.isHardError()) {
                return;
            }
            log.warn("rabbitmq connection shut down hard: {}", signal.getReason());
        });

        try {
            Channel channel = this.connection.createChannel();
            try {
                Topology.declareBase(channel, config.exchange(),
                        options.aeMaxLength(), options.dlqMaxLength());
            } finally {
                try {
                    channel.close();
                } catch (IOException | TimeoutException closeFailure) {
                    log.warn("failed to close topology channel: {}", closeFailure.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(this.connection);
            throw new TransportUnavailableException(
                    "failed to declare the events topology (exchange '" + config.exchange() + "')", e);
        }

        try {
            this.publisher = Publisher.open(this.connection, codec, config.exchange(),
                    stats, options.confirmTimeout());
        } catch (IOException | RuntimeException e) {
            closeQuietly(this.connection);
            throw new TransportUnavailableException("failed to open the confirm-mode publisher channel", e);
        }
        log.info("rabbitmq transport up: exchange='{}', vhost='{}', connection='{}'",
                config.exchange(), effectiveVhost(config.uri()), options.connectionName());
    }

    /** The exchange events are published to (from the config). */
    public String exchange() {
        return config.exchange();
    }

    /** The options this transport was built with. */
    public RabbitMqTransportOptions options() {
        return options;
    }

    @Override
    public CompletionStage<Void> publish(MessageEnvelope<? extends EventPayload> envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new TransportClosedException("transport is closed"));
        }
        return publisher.publish(envelope);
    }

    @Override
    public Subscription subscribe(String queueName, String bindingKey, MessageHandler handler) {
        TransportKeys.requireValidQueueName(queueName);
        TransportKeys.requireValidBindingKey(bindingKey);
        Objects.requireNonNull(handler, "handler must not be null");
        if (closed.get()) {
            throw new TransportClosedException("transport is closed");
        }

        Channel channel;
        try {
            channel = connection.createChannel();
            Topology.declareWorkerQueue(channel, config.exchange(), queueName,
                    bindingKey, options.deliveryLimit());
            channel.basicQos(options.prefetch(), false);
        } catch (IOException | RuntimeException e) {
            throw new SubscriptionException(
                    "failed to establish queue '" + queueName + "' bound to '" + bindingKey + "'", e);
        }

        // the callback hands the subscription back so it can deregister itself on close
        RabbitMqSubscription subscription = new RabbitMqSubscription(
                queueName, bindingKey, channel, subscriptions::remove);
        return registerConsumer(subscription, handler);
    }

    private Subscription registerConsumer(RabbitMqSubscription subscription, MessageHandler handler) {
        Channel channel = subscription.channel();
        try {
            String tag = channel.basicConsume(subscription.queueName(), false,
                    (consumerTag, delivery) -> onDelivery(handler, channel, delivery),
                    consumerTag -> {
                        log.warn("broker cancelled consumer '{}' on queue '{}' - the subscription is dead",
                                consumerTag, subscription.queueName());
                        subscription.markClosedFromBroker();
                    });
            subscription.consumerTag(tag);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException | TimeoutException closeFailure) {
                log.warn("failed to close consumer channel after register failure: {}", closeFailure.toString());
            }
            throw new SubscriptionException(
                    "failed to register consumer on queue '" + subscription.queueName() + "'", e);
        }
        subscriptions.add(subscription);
        log.info("subscribed '{}' -> '{}' (prefetch {}, delivery-limit {})",
                subscription.queueName(), subscription.bindingKey(), options.prefetch(), options.deliveryLimit());
        return subscription;
    }

    @Override
    public TransportStats stats() {
        return stats;
    }

    @Override
    public CompletionStage<Void> close() {
        return CompletableFuture.runAsync(this::shutdownQuietly, CLOSE_EXECUTOR)
                .exceptionally(throwable -> {
                    log.error("transport shutdown blew up; continuing best-effort", throwable);
                    return null;
                });
    }

    private void shutdownQuietly() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<RabbitMqSubscription> open = new ArrayList<>(subscriptions);
        for (RabbitMqSubscription subscription : open) {
            try {
                subscription.close().toCompletableFuture().join();
            } catch (RuntimeException e) {
                log.warn("failed to close subscription '{}': {}", subscription.queueName(), e.toString());
            }
        }
        publisher.close();
        closeQuietly(connection);
        log.info("rabbitmq transport closed");
    }

    // ----- delivery side ---------------------------------------------------------

    private void onDelivery(MessageHandler handler, Channel channel, com.rabbitmq.client.Delivery delivery) {
        try {
            processDelivery(handler, channel, delivery);
        } catch (Throwable t) {
            // last line of defense: a bug in the dispatch itself must not wedge the queue.
            // requeue would hot-loop on a deterministic bug, so this parks the frame in the DLQ -
            // everything short of a broker outage is better off there than circling
            log.error("delivery dispatch blew up; rejecting the frame to the dead-letter exchange", t);
            try {
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                stats.incrementDeadLettered();
            } catch (IOException | RuntimeException rejectFailure) {
                log.error("failed to reject after dispatch failure: {}", rejectFailure.toString());
            }
        }
    }

    private void processDelivery(MessageHandler handler, Channel channel, com.rabbitmq.client.Delivery delivery) {
        Envelope envelope = delivery.getEnvelope();
        AMQP.BasicProperties properties = delivery.getProperties();
        byte[] body = delivery.getBody();
        long deliveryTag = envelope.getDeliveryTag();

        DeliveryMetadata metadata = new DeliveryMetadata(
                envelope.isRedeliver(),
                deliveryCountHeader(properties, "x-delivery-count"),
                deliveryCountHeader(properties, "x-acquired-count"),
                envelope.getRoutingKey(),
                properties == null ? null : properties.getMessageId());
        if (metadata.redelivered()) {
            stats.incrementRedelivered();
        }

        MessageEnvelope<JsonNode> inbound;
        try {
            inbound = codec.decodeUntyped(body);
        } catch (RuntimeException e) {
            // structural validation lives in the codec - unsupported protocol
            // versions, broken payloads, oversized frames and garbage all land here
            stats.incrementMalformed();
            log.warn("dropping malformed frame on routing key '{}': {}", envelope.getRoutingKey(), e.toString());
            ackQuietly(channel, deliveryTag, "malformed frame");
            return;
        }
        if (codec.resolvePayloadType(inbound.eventType()).isEmpty()) {
            stats.incrementUnknownType();
            log.warn("dropping frame with unknown event type '{}' on routing key '{}'",
                    inbound.eventType(), envelope.getRoutingKey());
            ackQuietly(channel, deliveryTag, "unknown event type");
            return;
        }

        InboundMessage message = new InboundMessage(metadata, inbound);
        HandlerVerdict verdict = invokeWithLocalRetry(handler, message);
        resolveVerdict(channel, deliveryTag, verdict, metadata);
    }

    /**
     * Runs the handler with bounded in-process retries. A returning NACK or a
     * thrown exception both spend the same budget; the final outcome is a
     * verdict (possibly NACK_REQUEUE, which the caller routes on).
     */
    private HandlerVerdict invokeWithLocalRetry(MessageHandler handler, InboundMessage message) {
        int budget = Math.max(1, options.localRetryAttempts());
        HandlerVerdict verdict = HandlerVerdict.NACK_REQUEUE;
        for (int attempt = 1; ; attempt++) {
            try {
                HandlerVerdict candidate = handler.handle(message);
                if (candidate == null) {
                    throw new IllegalStateException("handler returned a null verdict");
                }
                verdict = candidate;
            } catch (Throwable t) {
                log.debug("handler blew up on event {} (attempt {}/{}): {}",
                        message.envelope().eventId(), attempt, budget, t.toString());
                verdict = HandlerVerdict.NACK_REQUEUE;
            }
            if (verdict != HandlerVerdict.NACK_REQUEUE || attempt >= budget) {
                return verdict;
            }
            if (!backoffSleep(attempt)) {
                // interrupted - stop retrying and let the broker decide
                return HandlerVerdict.NACK_REQUEUE;
            }
        }
    }

    private void resolveVerdict(Channel channel, long deliveryTag, HandlerVerdict verdict, DeliveryMetadata metadata) {
        switch (verdict) {
            case ACK -> ackQuietly(channel, deliveryTag, "processed");
            case REJECT -> {
                rejectQuietly(channel, deliveryTag, "permanent handler failure");
                stats.incrementDeadLettered();
            }
            case NACK_REQUEUE -> {
                int effective = RetryDecider.effectiveDeliveryCount(
                        metadata.deliveryCount(), metadata.acquiredCount(),
                        metadata.redelivered(), options.deliveryLimit());
                DeliveryDisposition disposition = RetryDecider.afterTransientFailure(effective, options.deliveryLimit());
                if (disposition == DeliveryDisposition.REQUEUE) {
                    nackQuietly(channel, deliveryTag, "transient failure, delivery " + effective + "/" + options.deliveryLimit());
                } else {
                    rejectQuietly(channel, deliveryTag, "delivery budget exhausted (" + effective + "/" + options.deliveryLimit() + ")");
                    stats.incrementDeadLettered();
                }
            }
        }
    }

    private boolean backoffSleep(int attempt) {
        Duration initial = config.retry() != null ? config.retry().initialBackoff() : Duration.ofMillis(200);
        Duration max = config.retry() != null ? config.retry().maxBackoff() : Duration.ofSeconds(10);
        long delay = initial.toMillis();
        long ceiling = Math.max(max.toMillis(), delay);
        for (int i = 1; i < attempt && delay < ceiling; i++) {
            delay = Math.min(delay * 2, ceiling);
        }
        if (delay <= 0) {
            return true;
        }
        // jitter keeps thundering herds off a recovering database
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 4));
        try {
            Thread.sleep(delay + jitter);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Quorum queues stamp {@code x-delivery-count} (failed deliveries) and
     * {@code x-acquired-count} (assignments); classic queues send neither and
     * this returns -1 for the missing header.
     */
    private static int deliveryCountHeader(AMQP.BasicProperties properties, String header) {
        if (properties == null || properties.getHeaders() == null) {
            return -1;
        }
        Object value = properties.getHeaders().get(header);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private void ackQuietly(Channel channel, long tag, String why) {
        try {
            channel.basicAck(tag, false);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to ack delivery {} ({}): {}", tag, why, e.toString());
        }
    }

    private void nackQuietly(Channel channel, long tag, String why) {
        try {
            channel.basicNack(tag, false, true);
            log.debug("nacked delivery {} for requeue: {}", tag, why);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to nack-requeue delivery {} ({}): {}", tag, why, e.toString());
        }
    }

    private void rejectQuietly(Channel channel, long tag, String why) {
        try {
            channel.basicReject(tag, false);
            log.warn("rejected delivery {} to the dead-letter exchange: {}", tag, why);
        } catch (IOException | RuntimeException e) {
            log.warn("failed to reject delivery {} ({}): {}", tag, why, e.toString());
        }
    }

    // ----- setup helpers ----------------------------------------------------------

    private static ConnectionFactory buildConnectionFactory(String uri, RabbitMqTransportOptions options) {
        final URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            throw new TransportUnavailableException(
                    "rabbitmq uri is not a valid amqp(s) uri (check config; credentials are not shown)");
        }
        ConnectionFactory factory = new ConnectionFactory();
        try {
            factory.setUri(uri);
        } catch (Exception e) {
            throw new TransportUnavailableException(
                    "rabbitmq uri is not a usable amqp(s) uri (check config; credentials are not shown)", e);
        }
        if (parsed.getPath() == null || parsed.getPath().isEmpty() || "/".equals(parsed.getPath())) {
            // no vhost in the uri - land on the continuum vhost instead of "/"
            factory.setVirtualHost(DEFAULT_VHOST);
        }
        factory.setRequestedHeartbeat((int) options.heartbeat().toSeconds());
        factory.setConnectionTimeout((int) options.connectionTimeout().toMillis());
        factory.setHandshakeTimeout((int) options.handshakeTimeout().toMillis());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(options.networkRecoveryInterval().toMillis());
        // rabbitmq shows the connection_name client property in the management UI
        Map<String, Object> clientProperties = new HashMap<>(factory.getClientProperties());
        clientProperties.put("connection_name", options.connectionName());
        factory.setClientProperties(clientProperties);
        return factory;
    }

    static String effectiveVhost(String uri) {
        try {
            URI parsed = new URI(uri);
            String path = parsed.getPath();
            return (path == null || path.isEmpty() || "/".equals(path)) ? DEFAULT_VHOST : path.substring(1);
        } catch (URISyntaxException e) {
            return DEFAULT_VHOST;
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close(CLOSE_TIMEOUT_MILLIS);
        } catch (IOException | RuntimeException e) {
            log.warn("connection close failed: {}", e.toString());
        }
    }
}


