package lol.candyriya.continuum.velocity.messaging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessageRouterTest {

    private final Logger logger = LoggerFactory.getLogger(PluginMessageRouterTest.class);

    @Test
    void framesAreCountedPerBackend() {
        var router = new DefaultPluginMessageRouter(logger, lol.candyriya.continuum.velocity.Metrics.NOOP);

        router.route(new FakeServerConnection("survival-1"), new byte[128]);
        router.route(new FakeServerConnection("survival-1"), new byte[256]);
        router.route(new FakeServerConnection("lobby"), new byte[64]);

        var health = router.health(true, ContinuumChannels.MAIN.getId(), 32768);
        assertEquals(3, health.acceptedFrames());
        assertEquals(2, health.framesByBackend().get("survival-1"));
        assertEquals(1, health.framesByBackend().get("lobby"));
        assertTrue(health.lastFrameAt().isPresent());
        assertEquals("continuum:main", health.channel());
        assertEquals(32768, health.maxPayloadBytes());
        assertTrue(health.enabled());
    }

    @Test
    void freshRouterHasNoTraffic() {
        var router = new DefaultPluginMessageRouter(logger, lol.candyriya.continuum.velocity.Metrics.NOOP);

        var health = router.health(false, ContinuumChannels.MAIN.getId(), 32768);
        assertEquals(0, health.acceptedFrames());
        assertTrue(health.framesByBackend().isEmpty());
        assertTrue(health.lastFrameAt().isEmpty());
        assertFalse(health.enabled());
    }
}
