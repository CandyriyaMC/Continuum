package lol.candyriya.continuum.messaging.rabbitmq;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning knobs that do not belong in the shared config record (which is
 * deliberately minimal). Defaults match the verified operational numbers:
 * prefetch 25, confirm window 10s, bounded catch-all and dead-letter
 * queues with reject-publish overflow so a stuck queue back-pressures the
 * publisher instead of silently dropping.
 *
 * <p>Connection settings live here too rather than in the shared config: the
 * shared record stays serializable with minimal ceremony, and everything the
 * transport needs at construction time is in one immutable place. All
 * durations must be positive.</p>
 *
 * @param prefetch               per-consumer prefetch count (basic.qos, global=false); default 25
 * @param confirmTimeout         how long an outstanding publish may go unconfirmed before
 *                               it fails with {@code ConfirmTimeoutException}; default 10s
 * @param localRetryAttempts     how many times the consumer invokes the handler for one
 *                               delivery before falling back to the broker decision table;
 *                               default 3
 * @param deliveryLimit          broker-enforced delivery cap for worker queues
 *                               ({@code x-delivery-limit} on quorum queues) and the ceiling
 *                               of the consumer-side retry decision; default 5
 * @param aeMaxLength            max entries in the unroutable-message catch-all queue;
 *                               overflow is rejected, not dropped; default 100_000
 * @param dlqMaxLength           max entries in the dead-letter queue; overflow is
 *                               rejected, not dropped; default 10_000
 * @param connectionName         name reported to the broker in connection.tune; shown in
 *                               management UI and logs; default {@code continuum}
 * @param heartbeat              AMQP heartbeat interval; default 10s
 * @param connectionTimeout      TCP + AMQP handshake timeout budget; default 10s
 * @param handshakeTimeout       raw AMQP handshake timeout; default 10s
 * @param networkRecoveryInterval pause between automatic recovery attempts; default 5s
 */
public record RabbitMqTransportOptions(int prefetch,
                                       Duration confirmTimeout,
                                       int localRetryAttempts,
                                       int deliveryLimit,
                                       int aeMaxLength,
                                       int dlqMaxLength,
                                       String connectionName,
                                       Duration heartbeat,
                                       Duration connectionTimeout,
                                       Duration handshakeTimeout,
                                       Duration networkRecoveryInterval) {

    public RabbitMqTransportOptions {
        if (prefetch < 1) {
            throw new IllegalArgumentException("prefetch must be >= 1, got " + prefetch);
        }
        confirmTimeout = positive(confirmTimeout, "confirmTimeout");
        if (localRetryAttempts < 1) {
            throw new IllegalArgumentException("localRetryAttempts must be >= 1, got " + localRetryAttempts);
        }
        if (deliveryLimit < 1) {
            throw new IllegalArgumentException("deliveryLimit must be >= 1, got " + deliveryLimit);
        }
        if (aeMaxLength < 1) {
            throw new IllegalArgumentException("aeMaxLength must be >= 1, got " + aeMaxLength);
        }
        if (dlqMaxLength < 1) {
            throw new IllegalArgumentException("dlqMaxLength must be >= 1, got " + dlqMaxLength);
        }
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("connectionName must not be blank");
        }
        connectionName = connectionName.trim();
        heartbeat = positive(heartbeat, "heartbeat");
        connectionTimeout = positive(connectionTimeout, "connectionTimeout");
        handshakeTimeout = positive(handshakeTimeout, "handshakeTimeout");
        networkRecoveryInterval = positive(networkRecoveryInterval, "networkRecoveryInterval");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    public static RabbitMqTransportOptions defaults() {
        return new RabbitMqTransportOptions(
                25, Duration.ofSeconds(10), 3, 5, 100_000, 10_000,
                "continuum", Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(5));
    }
}
