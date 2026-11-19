package lol.candyriya.continuum.messaging.rabbitmq;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RabbitMqTransportOptionsTest {

    @Test
    void defaultsMatchTheOperationalPlaybook() {
        RabbitMqTransportOptions defaults = RabbitMqTransportOptions.defaults();
        assertEquals(25, defaults.prefetch());
        assertEquals(Duration.ofSeconds(10), defaults.confirmTimeout());
        assertEquals(3, defaults.localRetryAttempts());
        assertEquals(5, defaults.deliveryLimit());
        assertEquals(100_000, defaults.aeMaxLength());
        assertEquals(10_000, defaults.dlqMaxLength());
        assertEquals("continuum", defaults.connectionName());
        assertEquals(Duration.ofSeconds(10), defaults.heartbeat());
        assertEquals(Duration.ofSeconds(10), defaults.connectionTimeout());
        assertEquals(Duration.ofSeconds(10), defaults.handshakeTimeout());
        assertEquals(Duration.ofSeconds(5), defaults.networkRecoveryInterval());
    }

    @Test
    void validationIsLoud() {
        RabbitMqTransportOptions defaults = RabbitMqTransportOptions.defaults();
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 0, 10, 3, 5, 1000, 1000, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 0, 3, 5, 1000, 1000, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 0, 5, 1000, 1000, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 3, 0, 1000, 1000, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 3, 5, 0, 1000, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 3, 5, 1000, 0, "n", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 3, 5, 1000, 1000, "  ", Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> copy(defaults, 25, 10, 3, 5, 1000, 1000, "n", Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
        assertDoesNotThrow(() -> copy(defaults, 50, 5, 2, 7, 1000, 2000, "continuum-it",
                Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5)));
    }

    private static RabbitMqTransportOptions copy(RabbitMqTransportOptions ignored,
                                                 int prefetch, long confirmTimeoutSeconds,
                                                 int localRetryAttempts, int deliveryLimit,
                                                 int aeMaxLength, int dlqMaxLength,
                                                 String connectionName,
                                                 Duration heartbeat, Duration connectionTimeout,
                                                 Duration handshakeTimeout, Duration networkRecoveryInterval) {
        return new RabbitMqTransportOptions(prefetch, Duration.ofSeconds(confirmTimeoutSeconds),
                localRetryAttempts, deliveryLimit, aeMaxLength, dlqMaxLength, connectionName,
                heartbeat, connectionTimeout, handshakeTimeout, networkRecoveryInterval);
    }
}
