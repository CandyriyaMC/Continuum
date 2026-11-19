package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.config.RabbitMqConfig;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The transport-selection contract: exactly one place decides whether events
 * flow through the broker or through the degraded path, driven by
 * {@code messaging.rabbitmq.enabled}.
 *
 * <p>The factory supplier is <b>lazy</b> — when RabbitMQ is disabled the
 * supplier is never invoked, so no connection attempt, no topology
 * declaration, not even a socket. When it is enabled, whatever {@code
 * factory.get()} returns is used as-is; construction failures propagate as
 * {@link TransportException}s and must not be swallowed by the caller — a
 * node that was promised a broker and cannot reach it has nothing useful to
 * do and should fail fast.</p>
 */
public final class TransportSelection {

    private TransportSelection() {
    }

    public static MessageTransport select(RabbitMqConfig config, Supplier<MessageTransport> rabbitMqFactory) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(rabbitMqFactory, "rabbitMqFactory must not be null");
        if (!config.enabled()) {
            return new AbsentTransport();
        }
        MessageTransport transport = rabbitMqFactory.get();
        if (transport == null) {
            throw new IllegalStateException("rabbitMqFactory returned null — refuse to run without an explicit transport");
        }
        return transport;
    }
}
