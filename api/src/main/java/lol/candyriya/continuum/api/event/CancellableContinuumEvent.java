package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Marker for the three events that fire <em>before</em> their operation and let a
 * listener veto it: {@link ContinuumLoadEvent}, {@link ContinuumSaveEvent} and
 * {@link ContinuumTransferEvent}.
 *
 * <p>Cancelling a veto event does not throw and does not interrupt dispatch: listeners
 * registered before and after the canceller all see the event, and the cancelled flag
 * becomes visible to them. The veto takes effect after dispatch completes —
 * a cancelled load means no restore is performed (the player joins with default state),
 * a cancelled save means this save is skipped, a cancelled transfer means the player
 * stays put.</p>
 *
 * <p>The flag is sticky: once cancelled, an event cannot be un-cancelled. A listener
 * observing a cancelled event should assume the operation will not happen and plan
 * accordingly.</p>
 *
 * <p>Threading: the get/set operations are safe from any thread, but cancellation is only
 * <em>honored</em> during the synchronous dispatch of the event itself. Cancelling from
 * another thread after dispatch completed is a no-op by then.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public non-sealed interface CancellableContinuumEvent extends ContinuumEvent {

    /**
     * Whether a listener has vetoed the operation.
     *
     * @return true once any listener cancelled; stays true for the rest of the event's life
     */
    boolean isCancelled();

    /**
     * Vetoes the operation. Sticky and idempotent.
     *
     * @param reason an optional English explanation, surfaced in logs and diagnostics; may be null
     */
    void cancel(@Nullable String reason);

    /**
     * The reason given with the cancellation, if any.
     *
     * @return the reason, or null when cancelled without one or not cancelled at all
     */
    @Nullable
    String cancelReason();
}
