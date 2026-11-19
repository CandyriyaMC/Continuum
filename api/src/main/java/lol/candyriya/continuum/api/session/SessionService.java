package lol.candyriya.continuum.api.session;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read access to session ownership state.
 *
 * <p>Obtained via {@code ContinuumProvider.get().sessions()}. All methods may be called
 * from any thread; storage-backed operations return a future that completes on
 * Continuum's infrastructure executor, never on a game thread.</p>
 *
 * <p>These methods are observational. Session ownership itself is decided by Continuum's
 * coordinator with fencing tokens checked inside the database write; no value returned
 * here can be used to override that.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface SessionService {

    /**
     * The current non-released session for a player, if any.
     *
     * <p>Released sessions are historical records, not current state: they are not
     * returned here. A player who is not logged in has no current session.</p>
     *
     * @param playerId the player, never null
     * @return the session, or empty when there is none
     * @throws NullPointerException when {@code playerId} is null
     */
    @NotNull
    CompletableFuture<Optional<SessionInfo>> session(@NotNull UUID playerId);

    /**
     * The active-ish sessions currently held by a backend (any state except
     * {@link SessionState#RELEASED}). Intended for admin diagnostics and cluster views.
     *
     * @param serverId the configured backend name, never null, never blank
     * @return the sessions, never null, possibly empty; iteration order is unspecified
     * @throws NullPointerException when {@code serverId} is null
     * @throws IllegalArgumentException when {@code serverId} is blank
     */
    @NotNull
    CompletableFuture<List<SessionInfo>> activeOn(@NotNull String serverId);
}
