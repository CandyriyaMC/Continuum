package lol.candyriya.continuum.messaging.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportKeysTest {

    @Test
    void bindingKeysAcceptTopicPatterns() {
        assertEquals("continuum.session.#", TransportKeys.requireValidBindingKey("continuum.session.#"));
        assertEquals("continuum.session.created", TransportKeys.requireValidBindingKey("continuum.session.created"));
        assertEquals("continuum.*", TransportKeys.requireValidBindingKey("continuum.*"));
        assertEquals("a.b.c.d", TransportKeys.requireValidBindingKey("a.b.c.d"));
    }

    @Test
    void bindingKeysRejectGarbage() {
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey(null));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey(""));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey("Continuum.Session.Created"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey("continuum..session"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey(".continuum.session"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey("continuum.session."));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey("continuum session"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidBindingKey("continuum/session"));
    }

    @Test
    void queueNamesAreSingleLevelTokens() {
        assertEquals("velocity", TransportKeys.requireValidQueueName("velocity"));
        assertEquals("backend-1.snapshots_2", TransportKeys.requireValidQueueName("backend-1.snapshots_2"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidQueueName(null));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidQueueName(""));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidQueueName("has space"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidQueueName("bad/slash"));
        assertThrows(IllegalArgumentException.class, () -> TransportKeys.requireValidQueueName("x".repeat(256)));
    }

    @Test
    void topicMatchingFollowsBrokerSemantics() {
        assertTrue(TransportKeys.matchesTopic("continuum.session.#", "continuum.session.created"));
        assertTrue(TransportKeys.matchesTopic("continuum.session.#", "continuum.session"));
        assertTrue(TransportKeys.matchesTopic("continuum.*", "continuum.session"));
        assertTrue(TransportKeys.matchesTopic("*.session.*", "continuum.session.created"));
        assertTrue(TransportKeys.matchesTopic("continuum.#", "continuum"));
        org.junit.jupiter.api.Assertions.assertFalse(TransportKeys.matchesTopic("continuum.*", "continuum.session.created"));
        org.junit.jupiter.api.Assertions.assertFalse(TransportKeys.matchesTopic("continuum.session", "continuum.session.created"));
        org.junit.jupiter.api.Assertions.assertFalse(TransportKeys.matchesTopic("*.session", "continuum.created.session"));
    }
}
