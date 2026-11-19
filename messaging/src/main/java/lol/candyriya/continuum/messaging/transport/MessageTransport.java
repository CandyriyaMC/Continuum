package lol.candyriya.continuum.messaging.transport;

import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;

import java.util.concurrent.CompletionStage;

/**
 * The one contract everything event-driven in Continuum programs against.
 *
 * <p>Semantics:</p>
 * <ul>
 * <li><b>Publish</b> is asynchronous. The returned stage completes after the
 * broker confirms the frame (publisher confirms) — or exceptionally with a
 * {@link PublishException} (channel failure, broker nack, confirmation
 * timeout). Encoding failures also complete exceptionally, with
 * {@link PublishException}. Implementations never block and never silently
 * drop: if the transport cannot deliver, the caller sees it.</li>
 * <li><b>Publishing</b> uses the envelope's {@code eventType} as the routing
 * key — event types are dotted lowercase namespaced keys by construction, so
 * routing and filtering stay in the same namespace.</li>
 * <li><b>Subscribe</b> declares a durable queue bound to the events exchange
 * with a topic pattern and registers {@code handler}. Ack/nack mechanics are
 * the transport's job; the ack/nack <i>decision</i> is the handler's job (see
 * {@link HandlerVerdict}). Throws {@link SubscriptionException} when the
 * topology cannot be established — degraded or absent transports return an
 * explicit {@link SubscriptionState#UNAVAILABLE} subscription instead of
 * pretending to work.</li>
 * <li><b>close()</b> stops consumers first, then publishers, releasing all
 * channels and the connection. Unacked messages are requeued by the broker.
 * Idempotent.</li>
 * </ul>
 *
 * <p>Deliveries are at-least-once, never exactly-once. Consumers that cannot
 * tolerate repeats must dedupe on {@code envelope.eventId()} — see
 * {@code IdempotentMessageHandler}.</p>
 *
 * <p>RabbitMQ is <i>not</i> the system of record. It carries hints and state
 * transitions; snapshots, sessions and fencing tokens live in the database.
 * Events are how components notice — they are not what components trust.</p>
 */
public interface MessageTransport {

    /**
     * Publishes {@code envelope} to the events exchange using its event type
     * as the routing key.
     *
     * @return a stage completing after the broker confirmation; exceptionally
     *         on any failure — {@link PublishException} for transport-side
     *         failures, {@link TransportUnavailableException} when this
     *         transport is a degraded no-op. The stage never completes
     *         silently.
     */
    CompletionStage<Void> publish(MessageEnvelope<? extends EventPayload> envelope);

    /**
     * Declares (idempotently) a durable queue bound to the events exchange
     * with {@code bindingKey}, and routes deliveries to {@code handler}.
     *
     * @param queueName  durable queue name; name the consumer group
     *                   ({@code velocity}, {@code backend-snapshot-events}),
     *                   never the host
     * @param bindingKey topic pattern, e.g. {@code continuum.session.#}
     * @return an ACTIVE subscription; UNAVAILABLE when this transport is degraded
     * @throws IllegalArgumentException on malformed queue name or binding key
     * @throws SubscriptionException    when the broker refuses the topology or the consumer
     */
    Subscription subscribe(String queueName, String bindingKey, MessageHandler handler);

    /** Live counters. Snapshot them; do not keep the instance for accounting. */
    TransportStats stats();

    /**
     * Graceful shutdown: stop consumers, cancel them at the broker, close
     * channels, then the connection. Completion after the broker acknowledged
     * the close is best-effort but deterministic in practice — calling
     * platforms typically {@code join()} this with a timeout.
     */
    CompletionStage<Void> close();
}
