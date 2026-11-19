package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.session.SessionInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when Continuum detects that a backend died holding a player's session — for
 * example on coordinator restart or on a lease expiry check — and the player's data may
 * be in a partially written state. The carried session has state
 * {@code RECOVERY_REQUIRED}; no one may write to the player's data until a human or the
 * recovery flow clears it.
 *
 * <p>Listeners here should alert operators. There is nothing to cancel: recovery is a
 * decision, not an operation.</p>
 *
 * <p><b>Thread:</b> fired on the thread that detected the condition — typically
 * Continuum's infrastructure executor. Treat it as an arbitrary thread.
 * <b>Cancellable:</b> no. <b>Mutability:</b> read-only.</p>
 *
 * @param session the session stuck in {@code RECOVERY_REQUIRED}, never null
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumRecoveryEvent(
        @NotNull SessionInfo session) implements ContinuumEvent {

    /** Canonical constructor with validation. */
    public ContinuumRecoveryEvent {
        Objects.requireNonNull(session, "session");
    }

    @Override
    @NotNull
    public UUID playerId() {
        return session.playerId();
    }
}
