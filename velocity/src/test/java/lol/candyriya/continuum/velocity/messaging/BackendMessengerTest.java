package lol.candyriya.continuum.velocity.messaging;

import lol.candyriya.continuum.velocity.Metrics;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendMessengerTest {

    private final Logger logger = LoggerFactory.getLogger(BackendMessengerTest.class);

    @Test
    void oversizedFrameIsRefusedBeforeItReachesTheNetwork() {
        var connection = new FakeServerConnection("survival-1");
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> 1024);

        boolean queued = messenger.send(connection, new byte[1025]);

        assertFalse(queued);
        assertFalse(connection.sendPluginMessageCalled, "oversized frame must not hit the wire");
    }

    @Test
    void frameWithinCapIsSentOnTheMainChannel() {
        var connection = new FakeServerConnection("survival-1");
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> 1024);
        byte[] payload = new byte[1024];

        boolean queued = messenger.send(connection, payload);

        assertTrue(queued);
        assertEquals(ContinuumChannels.MAIN, connection.lastChannel);
        assertArrayEquals(payload, connection.lastPayload);
    }

    @Test
    void frameExactlyAtCapIsSent() {
        var connection = new FakeServerConnection("survival-1");
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> 32768);

        assertTrue(messenger.send(connection, new byte[32768]));
    }

    @Test
    void backendRefusalIsReportedAsFalse() {
        var connection = new FakeServerConnection("survival-1", false);
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> 1024);

        assertFalse(messenger.send(connection, new byte[10]));
    }

    @Test
    void capIsReadFreshOnEverySend() {
        var cap = new int[]{1};
        var connection = new FakeServerConnection("survival-1");
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> cap[0]);

        assertFalse(messenger.send(connection, new byte[2]));
        cap[0] = 64;
        assertTrue(messenger.send(connection, new byte[2]));
    }

    @Test
    void channelAndCapAreExposedForDiagnostics() {
        var messenger = new BackendMessenger(logger, Metrics.NOOP, () -> 4096);

        assertSame(ContinuumChannels.MAIN, messenger.channel());
        assertEquals(4096, messenger.maxPayloadBytes());
    }
}
