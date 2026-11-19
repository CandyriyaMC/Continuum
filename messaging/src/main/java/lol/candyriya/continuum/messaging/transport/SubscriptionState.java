package lol.candyriya.continuum.messaging.transport;

/** Lifecycle state of a {@link Subscription}. */
public enum SubscriptionState {
    /** Registered and receiving. */
    ACTIVE,
    /** Closed by the caller (or the transport); broker requeues anything unacked. */
    CLOSED,
    /** The transport is absent (degraded mode); nothing will ever be delivered. */
    UNAVAILABLE
}
