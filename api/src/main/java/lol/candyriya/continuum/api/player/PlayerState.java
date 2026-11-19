package lol.candyriya.continuum.api.player;

import lol.candyriya.continuum.api.session.SessionInfo;
import lol.candyriya.continuum.api.snapshot.PlayerSnapshot;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A point-in-time diagnostics view of one player, aggregating everything Continuum knows:
 * the live session (if any) and the newest stored snapshot (if any).
 *
 * <p>Used by {@code PlayerService#state(UUID)}. Both components are nullable and the
 * record does not enforce that at least one is present — a player Continuum has never
 * seen is a perfectly valid answer, reported as two nulls rather than as an exception.
 * Distinguish &ldquo;no data&rdquo; (both null) from &ldquo;Continuum failed&rdquo;
 * (exceptional completion of the future) — they mean different things.</p>
 *
 * <p>The view is a snapshot: it can change the moment after you read it. It is not a
 * synchronization primitive and must not be used to make decisions about session
 * ownership; only Continuum's session manager does that.</p>
 *
 * @param session the player's current session, or null when there is none (offline, or released)
 * @param latestSnapshot the newest stored snapshot's metadata, or null when none exists
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record PlayerState(
        @Nullable SessionInfo session,
        @Nullable PlayerSnapshot latestSnapshot) {

    /**
     * Whether Continuum knows anything about the player at all.
     *
     * @return false when both session and latest snapshot are null
     */
    public boolean known() {
        return session != null || latestSnapshot != null;
    }
}
