package lol.candyriya.continuum.messaging.rabbitmq;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TopologyTest {

    @Test
    void workerQueueArgumentsUseQuorumAndArgsBasedDlx() {
        Map<String, Object> args = Topology.workerQueueArguments(5);
        assertEquals("quorum", args.get("x-queue-type"));
        assertEquals("continuum.dlx", args.get("x-dead-letter-exchange"));
        assertEquals(5, args.get("x-delivery-limit"));
        // arguments only - never policies; a broker without policy access must be able to run this
        assertFalse(args.containsKey("x-max-length"));
    }

    @Test
    void deadLetterQueueIsQuorumBoundedAndRejectOnOverflow() {
        Map<String, Object> args = Topology.dlqArguments(1_000);
        assertEquals("quorum", args.get("x-queue-type"));
        assertEquals(1_000, args.get("x-max-length"));
        assertEquals("reject-publish", args.get("x-overflow"));
    }

    @Test
    void alternateExchangeQueueIsBoundedAndRejectOnOverflow() {
        Map<String, Object> args = Topology.aeQueueArguments(500);
        assertEquals(500, args.get("x-max-length"));
        assertEquals("reject-publish", args.get("x-overflow"));
    }

    @Test
    void eventsExchangePointsAtTheAlternateExchange() {
        assertEquals("continuum.ae", Topology.eventsExchangeArguments().get("alternate-exchange"));
    }
}
