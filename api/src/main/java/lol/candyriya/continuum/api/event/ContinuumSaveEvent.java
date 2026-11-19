package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired before Continuum captures a player's state — on autosave, transfer, disconnect,
 * shutdown, manual trigger or API request. Cancellable: vetoing the event skips this
 * capture entirely (no snapshot is written, the player is not blocked, a debug line is
 * logged with the cancel reason).
 *
 * <p><b>Thread:</b> fired synchronously on the thread owning the player — on Paper the
 * server main thread; on Folia the player's entity region thread. <b>Cancellable:</b>
 * yes. <b>Mutability:</b> only the cancellation flag.</p>
 *
 * <p>Note: the reason tells you <em>why</em> the capture is happening; a veto is an
 * emergency brake, not a sampling mechanism — cancelling TRANSFER leaves the player
 * without a pre-transfer snapshot, which defeats the transfer's safety guarantees.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public final class ContinuumSaveEvent implements CancellableContinuumEvent {

    private final @NotNull UUID playerId;
    private final @NotNull SnapshotReason reason;
    private volatile boolean cancelled;
    private volatile @Nullable String cancelReason;

    /**
     * Creates the event. Constructed by Continuum, not by listeners.
     *
     * @param playerId the player being captured, never null
     * @param reason why the capture is happening, never null
     */
    public ContinuumSaveEvent(@NotNull UUID playerId, @NotNull SnapshotReason reason) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    @NotNull
    public UUID playerId() {
        return playerId;
    }

    /**
     * Why the capture is happening.
     *
     * @return the reason, never null
     */
    @NotNull
    public SnapshotReason reason() {
        return reason;
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
        if (!(o instanceof ContinuumSaveEvent that)) return false;
        return cancelled == that.cancelled
                && playerId.equals(that.playerId)
                && reason == that.reason
                && Objects.equals(cancelReason, that.cancelReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, reason, cancelled, cancelReason);
    }

    @Override
    public String toString() {
        return "ContinuumSaveEvent{playerId=" + playerId + ", reason=" + reason + ", cancelled=" + cancelled + "}";
    }
}
