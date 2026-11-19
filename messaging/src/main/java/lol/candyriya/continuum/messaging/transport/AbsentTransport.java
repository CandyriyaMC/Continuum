package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The explicit degraded mode for a JVM running without RabbitMQ. This is not
 * a mock and it is never silent: every publish fails the returned stage with
 * {@link TransportUnavailableException}, every subscription is born
 * {@link SubscriptionState#UNAVAILABLE}, and both log why. Callers that
 * require the broker must surface the failure; callers that can live without
 * it check the state and adapt. What must never happen — a critical event
 * "succeeding" into the void — cannot happen here.
 */
public final class AbsentTransport implements MessageTransport {

    private static final Logger log = LoggerFactory.getLogger(AbsentTransport.class);

    private final TransportStats stats = new TransportStats();
    private volatile boolean closed;

    public AbsentTransport() {
        log.warn("events transport is absent by configuration — nothing will be published or delivered; "
                + "critical event flows must either queue into the outbox or the broker must be enabled");
    }

    @Override
    public CompletionStage<Void> publish(MessageEnvelope<? extends EventPayload> envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        stats.incrementFailed();
        if (closed) {
            return CompletableFuture.failedFuture(new TransportClosedException("transport is closed"));
        }
        TransportUnavailableException failure = new TransportUnavailableException(
                "rabbitmq transport is disabled or absent; refusing to publish event type '"
                        + envelope.eventType() + "' (event id " + envelope.eventId() + ")");
        log.warn("refusing to publish '{}' — transport absent", envelope.eventType());
        return CompletableFuture.failedFuture(failure);
    }

    @Override
    public Subscription subscribe(String queueName, String bindingKey, MessageHandler handler) {
        TransportKeys.requireValidQueueName(queueName);
        TransportKeys.requireValidBindingKey(bindingKey);
        Objects.requireNonNull(handler, "handler must not be null");
        log.warn("subscription '{}' -> '{}' created on an absent transport; no deliveries will happen",
                queueName, bindingKey);
        return new InactiveSubscription(queueName, bindingKey);
    }

    @Override
    public TransportStats stats() {
        return stats;
    }

    @Override
    public CompletionStage<Void> close() {
        closed = true;
        return CompletableFuture.completedFuture(null);
    }
}
