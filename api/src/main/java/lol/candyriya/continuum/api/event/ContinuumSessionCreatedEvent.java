package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.session.SessionInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when Continuum assigns a new session to a player — at join time or after a
 * transfer. Fired once per session, after the fencing token is reserved in the database.
 *
 * <p><b>Thread:</b> fired on the thread that completed the session transition — typically
 * Continuum's infrastructure executor, <em>not</em> a platform scheduler thread. Treat it
 * as an arbitrary thread. <b>Cancellable:</b> no. <b>Mutability:</b> read-only.</p>
 *
 * @param session the newly created session, never null
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumSessionCreatedEvent(
        @NotNull SessionInfo session) implements ContinuumEvent {

    /** Canonical constructor with validation. */
    public ContinuumSessionCreatedEvent {
        Objects.requireNonNull(session, "session");
    }

    @Override
    @NotNull
    public UUID playerId() {
        return session.playerId();
    }
}
