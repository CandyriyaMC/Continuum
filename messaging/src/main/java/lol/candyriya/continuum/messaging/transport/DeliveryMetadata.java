package lol.candyriya.continuum.messaging.transport;

import java.util.Objects;

/**
 * Transport-neutral view of how the broker handed the message over. No
 * delivery tags or other broker-specific handles leak through here.
 *
 * @param redelivered   the broker flagged this as a redelivery
 * @param deliveryCount how many times the broker has counted a <i>failed</i>
 *                      delivery of this message, from the {@code
 *                      x-delivery-count} header (quorum queues; rejects and
 *                      crashes count, nacks do not). {@code -1} when the
 *                      broker does not report it (classic queues)
 * @param acquiredCount how many times the message has been <i>assigned</i>
 *                      to a consumer, from the {@code x-acquired-count}
 *                      header (quorum queues; nacks count). {@code -1} when
 *                      the broker does not report it
 * @param routingKey    the routing key the message was published with (the
 *                      event type)
 * @param messageId     the publisher-assigned message id, usually the envelope
 *                      event id; {@code null} when the publisher did not set one
 */
public record DeliveryMetadata(boolean redelivered, int deliveryCount, int acquiredCount,
                               String routingKey, String messageId) {

    public DeliveryMetadata {
        Objects.requireNonNull(routingKey, "routingKey must not be null");
    }

    /** Convenience: true when the broker knows this is at least the n-th try (either header). */
    public boolean atLeastDelivery(int n) {
        return Math.max(deliveryCount, acquiredCount) >= n;
    }
}
