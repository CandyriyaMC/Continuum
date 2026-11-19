package lol.candyriya.continuum.messaging.rabbitmq;

import com.rabbitmq.client.Channel;

import lol.candyriya.continuum.messaging.transport.Subscription;
import lol.candyriya.continuum.messaging.transport.SubscriptionState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Handle for one broker consumer. {@link #close()} cancels the consumer at
 * the broker ({@code basic.cancel}) and closes the channel; messages the
 * broker already pushed and we never acked go back to the queue, so closing
 * is safe at any time. Idempotent - calling close twice is a no-op.
 *
 * <p>A broker-initiated cancel (queue deleted, policy change, ...) marks the
 * subscription closed too - {@link #state()} flips to {@link
 * SubscriptionState#CLOSED} and later {@code close()} calls do nothing.</p>
 */
final class RabbitMqSubscription implements Subscription {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqSubscription.class);

    private final String queueName;
    private final String bindingKey;
    private final Channel channel;
    private final Consumer<RabbitMqSubscription> onClose;

    private volatile SubscriptionState state = SubscriptionState.ACTIVE;
    private volatile String consumerTag;

    RabbitMqSubscription(String queueName, String bindingKey, Channel channel,
                         Consumer<RabbitMqSubscription> onClose) {
        this.queueName = queueName;
        this.bindingKey = bindingKey;
        this.channel = channel;
        this.onClose = Objects.requireNonNull(onClose, "onClose must not be null");
    }

    @Override
    public String queueName() {
        return queueName;
    }

    @Override
    public String bindingKey() {
        return bindingKey;
    }

    Channel channel() {
        return channel;
    }

    void consumerTag(String consumerTag) {
        this.consumerTag = consumerTag;
    }

    @Override
    public SubscriptionState state() {
        return state;
    }

    /** Called when the broker cancels the consumer out from under us. */
    void markClosedFromBroker() {
        if (state == SubscriptionState.CLOSED) {
            return;
        }
        state = SubscriptionState.CLOSED;
        onClose.accept(this);
    }

    @Override
    public CompletionStage<Void> close() {
        if (state == SubscriptionState.CLOSED) {
            return CompletableFuture.completedFuture(null);
        }
        state = SubscriptionState.CLOSED;
        try {
            String tag = consumerTag;
            if (tag != null) {
                try {
                    channel.basicCancel(tag);
                } catch (IOException | RuntimeException e) {
                    // unacked messages are requeued by the broker on cancel/channel close anyway
                    log.warn("basic.cancel for consumer '{}' on queue '{}' failed: {}",
                            tag, queueName, e.toString());
                }
            }
            try {
                channel.close();
            } catch (IOException | TimeoutException | RuntimeException e) {
                log.warn("failed to close consumer channel for queue '{}': {}", queueName, e.toString());
            }
        } finally {
            onClose.accept(this);
        }
        return CompletableFuture.completedFuture(null);
    }
}
