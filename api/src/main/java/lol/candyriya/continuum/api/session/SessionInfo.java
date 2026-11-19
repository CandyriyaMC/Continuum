package lol.candyriya.continuum.api.session;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A point-in-time view of a player's session — the ownership record Continuum uses to
 * decide who may write what.
 *
 * <p>Instances are immutable and reflect the session at the moment the query was
 * executed. They are not a lock or a lease handle: changing the record locally changes
 * nothing on any backend.</p>
 *
 * @param playerId the player who owns the session, never null
 * @param serverId the backend holding (or last holding) the session: the configured
 *        server name, never null, never blank
 * @param state where the session is in its lifecycle, never null
 * @param fencingToken the monotonic token issued when the session was created, &ge; 0;
 *        a write is only valid against the current token, and Continuum enforces that
 *        check atomically in the database
 * @param since when the session (this state transition chain) started, never null
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record SessionInfo(
        @NotNull UUID playerId,
        @NotNull String serverId,
        @NotNull SessionState state,
        long fencingToken,
        @NotNull Instant since) {

    /**
     * Canonical constructor with validation.
     *
     * @throws NullPointerException when {@code playerId}, {@code serverId}, {@code state}
     *         or {@code since} is null
     * @throws IllegalArgumentException when {@code serverId} is blank or
     *         {@code fencingToken} is negative
     */
    public SessionInfo {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(serverId, "serverId");
        if (serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
        Objects.requireNonNull(state, "state");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        Objects.requireNonNull(since, "since");
    }
}
