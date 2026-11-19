package lol.candyriya.continuum.paper.gate;

import org.bukkit.entity.Player;

/**
 * The restore work itself. Wired to the gate by the backend composition when
 * the synchronization layer is available.
 *
 * <p>Called after the gate has frozen the player (spectator, hidden,
 * invulnerable) — never before. Implementations do their work wherever they
 * need to (typically off the game loop) and then call
 * {@link RestoreGate#markReady(java.util.UUID)} or
 * {@link RestoreGate#markFailed(java.util.UUID, String)}.</p>
 *
 * <p>If an implementation does neither within the configured restore timeout,
 * the gate treats the restore as failed: the player is kicked and the failure
 * is recorded against them.</p>
 */
@FunctionalInterface
public interface RestoreHandler {

    /** Start the restore for {@code player}. Runs on the player's entity thread; must return promptly — do the work off-thread. */
    void restore(Player player);
}
