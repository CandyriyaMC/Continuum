package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.session.SessionInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when a session ends and ownership is handed back — on quit, kick, transfer
 * completion, or backend shutdown. Fired once per session, after the final fencing-token
 * check against the database. The carried session has state {@code RELEASED}.
 *
 * <p><b>Thread:</b> fired on the thread that completed the session transition — typically
 * Continuum's infrastructure executor, <em>not</em> a platform scheduler thread. Treat it
 * as an arbitrary thread. <b>Cancellable:</b> no. <b>Mutability:</b> read-only.</p>
 *
 * @param session the released session, never null; its state is {@code RELEASED}
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumSessionReleasedEvent(
        @NotNull SessionInfo session) implements ContinuumEvent {

    /** Canonical constructor with validation. */
    public ContinuumSessionReleasedEvent {
        Objects.requireNonNull(session, "session");
    }

    @Override
    @NotNull
    public UUID playerId() {
        return session.playerId();
    }
}
