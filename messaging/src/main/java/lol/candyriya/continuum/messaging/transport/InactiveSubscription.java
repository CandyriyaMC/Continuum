package lol.candyriya.continuum.messaging.transport;

/**
 * A subscription that will never deliver, returned by the degraded
 * (RabbitMQ-absent) transport. It exists so callers can wire the same code
 * path either way without null checks — {@link SubscriptionState#UNAVAILABLE}
 * is the explicit signal that no events will flow, and {@link #close()} is a
 * well-behaved no-op.
 */
final class InactiveSubscription implements Subscription {

    private final String queueName;
    private final String bindingKey;
    private volatile SubscriptionState state = SubscriptionState.UNAVAILABLE;

    InactiveSubscription(String queueName, String bindingKey) {
        this.queueName = queueName;
        this.bindingKey = bindingKey;
    }

    @Override
    public String queueName() {
        return queueName;
    }

    @Override
    public String bindingKey() {
        return bindingKey;
    }

    @Override
    public SubscriptionState state() {
        return state;
    }

    @Override
    public java.util.concurrent.CompletionStage<Void> close() {
        state = SubscriptionState.CLOSED;
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
