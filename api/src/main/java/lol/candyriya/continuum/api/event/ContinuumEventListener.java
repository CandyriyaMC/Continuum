package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * A subscriber to one event type. Functional interface — register with a lambda or method
 * reference via {@link EventService#subscribe(Class, ContinuumEventListener)}.
 *
 * <p>Implementations may throw anything: exceptions are caught by Continuum, logged with
 * the listener and event type, and never propagated to the sync pipeline or to other
 * listeners. They must not block the dispatching thread — do heavy work on your own
 * executor.</p>
 *
 * @param <E> the event type, a member of the sealed {@link ContinuumEvent} hierarchy
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
@FunctionalInterface
public interface ContinuumEventListener<E extends ContinuumEvent> {

    /**
     * Called synchronously when the event fires.
     *
     * @param event the event, never null
     * @throws Exception any failure; caught and logged by Continuum, never propagated
     */
    void onEvent(@NotNull E event) throws Exception;
}
