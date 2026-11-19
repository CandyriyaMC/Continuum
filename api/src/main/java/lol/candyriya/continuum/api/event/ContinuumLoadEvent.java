package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before Continuum restores a player's state — at join time, on rollback, or on an
 * admin-forced restore. Cancellable: vetoing the event skips the restore entirely (the
 * player joins with default state and a warning is logged with the cancel reason).
 *
 * <p>Use it to suspend Continuum for a player — maintenance mode, debugging on staging,
 * or an A/B rollout you want to keep a specific player out of. It is not a hook for
 * changing what gets restored; there is no state to mutate here.</p>
 *
 * <p><b>Thread:</b> fired synchronously on the thread that will perform the restore —
 * on Paper the server main thread; on Folia the player's entity region thread.
 * <b>Cancellable:</b> yes. <b>Mutability:</b> only the cancellation flag.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public final class ContinuumLoadEvent implements CancellableContinuumEvent {

    private final @NotNull UUID playerId;
    private volatile boolean cancelled;
    private volatile @Nullable String cancelReason;

    /**
     * Creates the event. Constructed by Continuum, not by listeners.
     *
     * @param playerId the player being restored, never null
     */
    public ContinuumLoadEvent(@NotNull UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
    }

    @Override
    @NotNull
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void cancel(@Nullable String reason) {
        this.cancelReason = reason;
        this.cancelled = true;
    }

    @Override
    @Nullable
    public String cancelReason() {
        return cancelReason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContinuumLoadEvent that)) return false;
        return cancelled == that.cancelled
                && playerId.equals(that.playerId)
                && Objects.equals(cancelReason, that.cancelReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, cancelled, cancelReason);
    }

    @Override
    public String toString() {
        return "ContinuumLoadEvent{playerId=" + playerId + ", cancelled=" + cancelled + "}";
    }
}
