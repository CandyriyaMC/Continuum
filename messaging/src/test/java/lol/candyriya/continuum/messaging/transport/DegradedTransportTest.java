package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.config.RabbitMqConfig;
import lol.candyriya.continuum.common.config.RabbitMqRetry;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.ProtocolEvents;
import lol.candyriya.continuum.common.protocol.payload.SessionReleasedPayload;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DegradedTransportTest {

    private static RabbitMqConfig config(boolean enabled) {
        return new RabbitMqConfig(enabled, "amqp://localhost:1/", "continuum.events",
                new RabbitMqRetry(1, Duration.ofMillis(10), Duration.ofMillis(10)));
    }

    private static MessageEnvelope<SessionReleasedPayload> sample() {
        return MessageEnvelope.create(ProtocolEvents.SESSION_RELEASED,
                "corr-" + UUID.randomUUID(),
                new SessionReleasedPayload(UUID.randomUUID(), "backend-1", 42L));
    }

    private static Throwable failureCause(CompletionStage<Void> stage) {
        try {
            stage.toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            return e.getCause();
        }
        throw new AssertionError("stage completed successfully, expected a failure");
    }

    @Test
    void disabledConfigSelectsAbsentTransportAndNeverTouchesTheFactory() {
        AtomicBoolean factoryCalled = new AtomicBoolean();
        MessageTransport transport = TransportSelection.select(config(false), () -> {
            factoryCalled.set(true);
            throw new AssertionError("factory must not run when rabbitmq is disabled");
        });

        assertEquals(AbsentTransport.class, transport.getClass());
        assertTrue(!factoryCalled.get());
    }

    @Test
    void absentTransportFailsLoudlyOnPublishAndCountsIt() {
        MessageTransport transport = TransportSelection.select(config(false), AbsentTransport::new);

        Throwable failure = failureCause(transport.publish(sample()));
        assertSame(TransportUnavailableException.class, failure.getClass());
        assertEquals(1, transport.stats().snapshot().failed());
        assertEquals(0, transport.stats().snapshot().confirmed());
        assertEquals(0, transport.stats().snapshot().published());
    }

    @Test
    void absentTransportSubscriptionIsExplicitlyUnavailable() {
        MessageTransport transport = TransportSelection.select(config(false), AbsentTransport::new);

        Subscription subscription = transport.subscribe("velocity", "continuum.session.#", message -> HandlerVerdict.ACK);
        assertEquals(SubscriptionState.UNAVAILABLE, subscription.state());

        subscription.close().toCompletableFuture().join();
        assertEquals(SubscriptionState.CLOSED, subscription.state());
        // close is idempotent
        subscription.close().toCompletableFuture().join();
    }

    @Test
    void enabledConfigUsesTheFactoryVerbatim() {
        MessageTransport sentinel = new AbsentTransport();
        MessageTransport selected = TransportSelection.select(config(true), () -> sentinel);
        assertSame(sentinel, selected);
    }

    @Test
    void factoryReturningNullIsALoudFailureNotASilentOne() {
        assertThrows(IllegalStateException.class, () -> TransportSelection.select(config(true), () -> null));
    }

    @Test
    void closedAbsentTransportRefusesFurtherPublishes() {
        MessageTransport transport = TransportSelection.select(config(false), AbsentTransport::new);
        transport.close().toCompletableFuture().join();

        assertSame(TransportClosedException.class, failureCause(transport.publish(sample())).getClass());
    }
}
