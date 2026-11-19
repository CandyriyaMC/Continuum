package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;

/**
 * A handle to an event subscription. Returned by
 * {@link EventService#subscribe(Class, ContinuumEventListener)}; close it to stop
 * receiving that event.
 *
 * <p>An in-flight dispatch that has already started will still deliver the event to this
 * listener — unsubscribing prevents <em>future</em> dispatches only. {@link #unsubscribe()}
 * and {@link #close()} are idempotent and safe to call from any thread; there is no
 * guarantee the unsubscribe is observed by a concurrently firing event.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public interface EventSubscription extends AutoCloseable {

    /**
     * Stops receiving the event. Idempotent.
     */
    void unsubscribe();

    /**
     * Whether the subscription is still active.
     *
     * @return true until {@link #unsubscribe()} (or {@link #close()}) is called
     */
    boolean isActive();

    /**
     * Equivalent to {@link #unsubscribe()} — so the subscription can be used in
     * try-with-resources. Never throws.
     */
    @Override
    default void close() {
        unsubscribe();
    }
}
