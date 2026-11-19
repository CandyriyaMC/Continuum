package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;

/**
 * Retry policy for failed RabbitMQ publishes and consumer deliveries that
 * exhaust attempts (they land in the DLX afterwards).
 *
 * @param maxAttempts     total attempts before giving up (0 = no retry, straight to DLQ)
 * @param initialBackoff  delay before the first retry
 * @param maxBackoff      backoff ceiling; consecutive delays approach this exponentially
 */
@ConfigSerializable
public record RabbitMqRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public RabbitMqRetry {
        if (initialBackoff == null) {
            initialBackoff = Duration.ofMillis(200);
        }
        if (maxBackoff == null) {
            maxBackoff = Duration.ofSeconds(10);
        }
    }
}
