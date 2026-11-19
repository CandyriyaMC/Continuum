package lol.candyriya.continuum.messaging.rabbitmq;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

/**
 * The declared topology, and nothing else. Exchanges and queues are declared
 * idempotently with arguments (never policies) so the broker needs no
 * {@code rabbitmqctl} access and a policy change can never silently alter
 * correctness-critical wiring.
 *
 * <pre>
 *   producer ──► continuum.events (topic, durable,
 *                alternate-exchange = continuum.ae)
 *                      │
 *                      ▼  (queue bound by the consumer with its own pattern)
 *                consumer queues (quorum, x-dead-letter-exchange = continuum.dlx,
 *                                x-delivery-limit &lt;n&gt;)
 *                      │ reject / limit exhausted
 *                      ▼
 *                continuum.dlx (topic) ──► # ──► continuum.events.dlq (quorum)
 *
 *   unroutable from continuum.events
 *                      ▼
 *                continuum.ae (fanout) ──► continuum.events.ae (durable, bounded)
 * </pre>
 *
 * <p>The dead-letter queue is quorum and bounded with reject-publish
 * overflow: when it is full, publishers and rejecting consumers get explicit
 * failures rather than silent end-of-story. The unroutable catch-all works
 * the same way — mandatory publishes make sure an AE hit is a visible, 
 * countable event, not a vanished one.</p>
 */
public final class Topology {

    /** Catch-all for publishes that matched nothing; bound 1:1 to {@link #AE_QUEUE}. */
    public static final String AE_EXCHANGE = "continuum.ae";

    /** Durable queue holding unroutable messages for inspection and alerting. */
    public static final String AE_QUEUE = "continuum.events.ae";

    /** Dead-letter exchange for worker queues. */
    public static final String DLX_EXCHANGE = "continuum.dlx";

    /** Terminal dead-letter queue (quorum, bounded, reject-publish). */
    public static final String DLQ_QUEUE = "continuum.events.dlq";

    /** Binding used to route everything dead-lettered into {@link #DLQ_QUEUE}. */
    private static final String DLQ_BINDING = "#";

    private Topology() {
    }

    // ----- pure argument builders (unit-tested without a broker) ---------------

    /** Arguments for the unroutable catch-all queue: bounded, reject on overflow. */
    public static Map<String, Object> aeQueueArguments(int maxLength) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", maxLength);
        args.put("x-overflow", "reject-publish");
        return args;
    }

    /** Arguments for the dead-letter queue: quorum, bounded, reject-publish on overflow. */
    public static Map<String, Object> dlqArguments(int maxLength) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-queue-type", "quorum");
        args.put("x-max-length", maxLength);
        args.put("x-overflow", "reject-publish");
        return Map.copyOf(args);
    }

    /** Arguments for the events exchange: unroutable messages fall into the AE. */
    public static Map<String, Object> eventsExchangeArguments() {
        return Map.of("alternate-exchange", AE_EXCHANGE);
    }

    /**
     * Arguments for a worker queue: quorum, dead-lettering into the DLX, and a
     * broker-enforced delivery limit as the backstop to the consumer-side
     * retry budget.
     */
    public static Map<String, Object> workerQueueArguments(int deliveryLimit) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-queue-type", "quorum");
        args.put("x-dead-letter-exchange", DLX_EXCHANGE);
        args.put("x-delivery-limit", deliveryLimit);
        return Map.copyOf(args);
    }

    // ----- declaration (needs a live channel) ----------------------------------

    /**
     * Declares exchanges and the system queues. Idempotent — calling it at
     * every boot with the same arguments is a no-op on the broker.
     */
    public static void declareBase(Channel channel, String eventsExchange,
                                   int aeMaxLength, int dlqMaxLength) throws IOException {
        // AE first: the events exchange references it at declare time on strict brokers
        channel.exchangeDeclare(AE_EXCHANGE, BuiltinExchangeType.FANOUT, true);
        channel.queueDeclare(AE_QUEUE, true, false, false, aeQueueArguments(aeMaxLength));
        channel.queueBind(AE_QUEUE, AE_EXCHANGE, "");

        channel.exchangeDeclare(DLX_EXCHANGE, BuiltinExchangeType.TOPIC, true);
        channel.queueDeclare(DLQ_QUEUE, true, false, false, dlqArguments(dlqMaxLength));
        channel.queueBind(DLQ_QUEUE, DLX_EXCHANGE, DLQ_BINDING);

        channel.exchangeDeclare(eventsExchange, BuiltinExchangeType.TOPIC, true, false,
                eventsExchangeArguments());
    }

    /**
     * Declares (idempotently) a durable worker queue bound to the events
     * exchange with {@code bindingKey}.
     */
    public static void declareWorkerQueue(Channel channel, String eventsExchange,
                                          String queueName, String bindingKey,
                                          int deliveryLimit) throws IOException {
        channel.queueDeclare(queueName, true, false, false, workerQueueArguments(deliveryLimit));
        channel.queueBind(queueName, eventsExchange, bindingKey);
    }
}
