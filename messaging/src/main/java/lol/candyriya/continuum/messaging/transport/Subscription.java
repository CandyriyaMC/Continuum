package lol.candyriya.continuum.messaging.transport;

import java.util.concurrent.CompletionStage;

/**
 * Handle for one consumer registration: a durable queue bound to the events
 * exchange, with the broker pushing deliveries to the handler.
 *
 * <p>{@link #close()} cancels the consumer and closes its channel. Messages
 * not yet acknowledged at that point are requeued by the broker — closing is
 * safe at any time.</p>
 */
public interface Subscription {

    /** The durable queue backing this subscription. */
    String queueName();

    /** The topic pattern this queue is bound with (the namespaced key). */
    String bindingKey();

    SubscriptionState state();

    /** Cancel the consumer and close the channel. Idempotent. */
    CompletionStage<Void> close();
}
