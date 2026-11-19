package lol.candyriya.continuum.api.player;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Diagnostics about a player as Continuum sees them.
 *
 * <p>Obtained via {@code ContinuumProvider.get().players()}. Safe to call from any
 * thread; the returned future completes on Continuum's infrastructure executor.</p>
 *
 * <p>This is a read-only view. It never triggers a capture, a restore or a session
 * transition, and it never fails just because the player is offline — it reports what
 * Continuum knows, which may be nothing.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface PlayerService {

    /**
     * The player's current Continuum state: live session (if any) plus newest stored
     * snapshot metadata (if any).
     *
     * <p>Never completes null. A player Continuum has never seen yields a
     * {@link PlayerState} with both components null — not an exception. Storage failures
     * complete the future exceptionally.</p>
     *
     * @param playerId the player, never null
     * @return the view; never completes null
     * @throws NullPointerException when {@code playerId} is null
     */
    @NotNull
    CompletableFuture<PlayerState> state(@NotNull UUID playerId);
}
