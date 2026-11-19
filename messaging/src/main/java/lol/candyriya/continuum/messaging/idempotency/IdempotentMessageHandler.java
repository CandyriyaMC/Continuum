package lol.candyriya.continuum.messaging.idempotency;

import lol.candyriya.continuum.messaging.transport.HandlerVerdict;
import lol.candyriya.continuum.messaging.transport.InboundMessage;
import lol.candyriya.continuum.messaging.transport.MessageHandler;
import lol.candyriya.continuum.messaging.transport.TransportStats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Wraps a delegate in the standard dedup-on-event-id guard.
 *
 * <p>Order of operations is deliberate — check before doing anything, mark
 * only after the delegate said {@link HandlerVerdict#ACK}:</p>
 * <ol>
 * <li>{@code isProcessed(group, eventId)} → true: count it, return ACK without
 * calling the delegate. The side effect happened at some point in the past;
 * repeating it now is what would be wrong.</li>
 * <li>Delegate runs. On ACK the id is marked processed.</li>
 * <li>On NACK_REQUEUE/REJECT the id is <b>not</b> marked — a retry (or a
 * dead-letter tool, or a human) must see the event unmarked so the flow can
 * be re-driven.</li>
 * </ol>
 *
 * <p>Crash window: delegate succeeds, process dies before marking → on
 * redelivery the delegate runs again. That is inherent to non-transactional
 * stores and is why durable implementations should mark inside the business
 * transaction; the guard still eliminates the common duplicate-delivery case.</p>
 *
 * <p>Exceptions from the delegate propagate unchanged — the transport maps
 * them onto the retry/dead-letter table.</p>
 */
public final class IdempotentMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentMessageHandler.class);

    private final String consumerGroup;
    private final ProcessedEventStore store;
    private final MessageHandler delegate;
    private final TransportStats stats; // may be null in tests

    public IdempotentMessageHandler(String consumerGroup, ProcessedEventStore store, MessageHandler delegate) {
        this(consumerGroup, store, delegate, null);
    }

    public IdempotentMessageHandler(String consumerGroup, ProcessedEventStore store,
                                    MessageHandler delegate, TransportStats stats) {
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        if (consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup must not be blank");
        }
        this.consumerGroup = consumerGroup;
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.stats = stats;
    }

    @Override
    public HandlerVerdict handle(InboundMessage message) throws Exception {
        UUID eventId = message.envelope().eventId();
        if (store.isProcessed(consumerGroup, eventId)) {
            if (stats != null) {
                stats.incrementDuplicatesSkipped();
            }
            log.debug("duplicate event {} (group '{}') — acking without touching the delegate",
                    eventId, consumerGroup);
            return HandlerVerdict.ACK;
        }
        HandlerVerdict verdict = delegate.handle(message);
        if (verdict == HandlerVerdict.ACK) {
            store.markProcessed(consumerGroup, eventId);
        }
        return verdict;
    }
}
