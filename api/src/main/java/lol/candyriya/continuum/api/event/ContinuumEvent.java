package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The sealed root of Continuum's event model. Every event Continuum fires is a member of
 * this hierarchy; new event types can only appear as new permitted subtypes.
 *
 * <p>All events carry a player id, except where genuinely not applicable — the nullable
 * case exists for {@link ContinuumFailureEvent} fired for global failures (storage
 * outage) that are not tied to one player.</p>
 *
 * <p>Threading: each concrete event documents the thread it fires on. Events are
 * dispatched synchronously to listeners in registration order; see {@code EventService}
 * for the full dispatch and error-isolation semantics.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public sealed interface ContinuumEvent permits
        CancellableContinuumEvent,
        ContinuumRestoreEvent,
        ContinuumModuleCaptureEvent,
        ContinuumModuleRestoreEvent,
        ContinuumSessionCreatedEvent,
        ContinuumSessionReleasedEvent,
        ContinuumRecoveryEvent,
        ContinuumFailureEvent {

    /**
     * The player this event is about, or null when the event has no single player
     * (only possible for {@link ContinuumFailureEvent}).
     *
     * @return the player UUID, or null
     */
    @Nullable
    UUID playerId();
}
