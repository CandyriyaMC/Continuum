package lol.candyriya.continuum.paper.orchestration;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The quit hook of the sync pipeline: final full capture, then session release.
 *
 * <p>Both operations are queued to the per-player FIFO so the final write and
 * the release are strictly ordered behind everything else that was in flight.
 * Fire-and-forget: the quit path never blocks the game loop; a capture that
 * cannot finish in time is an error log, not a hung server.</p>
 */
public final class OrchestrationListeners implements Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationListeners.class);

    private final SnapshotManager snapshots;

    public OrchestrationListeners(SnapshotManager snapshots) {
        this.snapshots = snapshots;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        java.util.UUID playerId = player.getUniqueId();
        try {
            // full capture first; releaseSession is queued behind the capture on the same lane
            snapshots.captureFinal(player).whenComplete((report, err) -> {
                if (err != null) {
                    LOGGER.error("final capture failed for {}; releasing the session anyway", playerId, unwrap(err));
                }
                snapshots.releaseSession(playerId).whenComplete((released, releaseErr) -> {
                    if (releaseErr != null) {
                        LOGGER.error("session release failed for {} after quit", playerId, unwrap(releaseErr));
                    }
                });
            });
        } catch (Throwable t) {
            LOGGER.error("could not start the final capture for {}", playerId, t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
