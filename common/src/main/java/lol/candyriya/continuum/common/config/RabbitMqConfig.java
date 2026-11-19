package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;

/**
 * RabbitMQ transport tuning.
 *
 * @param enabled  publish/consume events through the broker; when false the
 *                 outbox relay stays idle and events queue in the database
 * @param uri      amqp(s) connection string; may embed credentials, never logged
 * @param exchange durable exchange events are published to (DLX is derived from it)
 * @param retry    publish/consume retry policy before dead-lettering
 */
@ConfigSerializable
public record RabbitMqConfig(boolean enabled, String uri, String exchange, RabbitMqRetry retry) {

    public RabbitMqConfig {
        if (retry == null) {
            retry = new RabbitMqRetry(5, Duration.ofMillis(200), Duration.ofSeconds(10));
        }
    }

    @Override
    public String toString() {
        // credentials ride inside the uri — mask it
        return "RabbitMqConfig[enabled=%s, uri=<masked>, exchange=%s, retry=%s]"
                .formatted(enabled, exchange, retry);
    }
}
