package lol.candyriya.continuum.paper.gate;

import org.bukkit.GameMode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State of one player inside the gate window: from just after they join until
 * the restore lifts the hold (or fails and kicks them).
 *
 * <p>{@link #restoring} is the single boolean every gate listener consults —
 * one flag per gated player, flipped exactly once, never reset for reuse (a
 * fresh session is created per join).</p>
 */
final class GateSession {

    final UUID playerId;
    final GameMode previousGameMode;
    final boolean previousInvulnerable;
    /** flips to false exactly once, when the hold is lifted or the session ends. */
    final AtomicBoolean restoring = new AtomicBoolean(true);
    final CompletableFuture<RestoreOutcome> future = new CompletableFuture<>();
    /** armed while a restore is expected; null when no timeout is configured or restore is synchronous. */
    volatile ScheduledFuture<?> timeoutTask;
    final long createdNanos = System.nanoTime();

    GateSession(UUID playerId, GameMode previousGameMode, boolean previousInvulnerable) {
        this.playerId = playerId;
        this.previousGameMode = previousGameMode;
        this.previousInvulnerable = previousInvulnerable;
    }

    boolean isRestoring() {
        return restoring.get();
    }
}
