package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Subscription point for Continuum's event model.
 *
 * <p>Obtained via {@code ContinuumProvider.get().events()}. Subscriptions are scoped to
 * the lifetime of the owning plugin: a platform that shuts Continuum down also releases
 * all subscriptions. Third-party plugins should unsubscribe in their own shutdown hook
 * or simply rely on it.</p>
 *
 * <p><b>Dispatch semantics.</b> Events are delivered synchronously, in registration
 * order, on the thread documented by each concrete event. A listener may itself
 * subscribe to or unsubscribe from other events while handling one; the change takes
 * effect for subsequent dispatches, not the in-flight one.</p>
 *
 * <p><b>Error policy.</b> Exceptions thrown by a listener are caught and logged at WARN
 * together with the listener class, the event type and the stack trace; dispatch then
 * continues with the next listener. A throwing listener never aborts an operation, never
 * reaches other listeners with a failure, and is not automatically unsubscribed.</p>
 *
 * <p><b>Ordering and cancellation.</b> There are no listener priorities and no
 * {@code @EventHandler}-style annotations — registration order is the only ordering. For
 * cancellable events, a cancelled operation is not executed once dispatch completes;
 * listeners registered after the canceller still observe the event and may inspect
 * {@link CancellableContinuumEvent#isCancelled()} to back off early.</p>
 *
 * <p><b>Thread-safety.</b> {@link #subscribe(Class, ContinuumEventListener)} and the
 * returned subscriptions are safe to call from any thread, including from inside an
 * {@code onEvent} handler.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public interface EventService {

    /**
     * Subscribes to one event type.
     *
     * @param <E> the event type
     * @param type the event class, never null; must be a concrete member of the sealed
     *        {@link ContinuumEvent} hierarchy — subscriptions match exactly one class,
     *        they do not fire for subtypes
     * @param listener the subscriber, never null
     * @return the subscription handle; unsubscribe or close it to stop receiving events
     * @throws NullPointerException when {@code type} or {@code listener} is null
     */
    @NotNull
    <E extends ContinuumEvent> EventSubscription subscribe(@NotNull Class<E> type, @NotNull ContinuumEventListener<E> listener);
}
