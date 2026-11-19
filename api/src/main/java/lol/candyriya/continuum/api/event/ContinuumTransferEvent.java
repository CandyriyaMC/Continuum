package lol.candyriya.continuum.api.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when a backend-to-backend transfer is initiated — on the proxy side, before any
 * backend has been told to release or restore anything. Cancellable: vetoing the event
 * aborts the transfer (the player stays on the current backend and the proxy command is
 * not carried out).
 *
 * <p>The destination may not be known yet when the event fires — a player who typed
 * {@code /server} without an argument, or a dynamic backend selection. Treat
 * {@link #toBackendId()} as the intended destination when present, not as a guarantee.</p>
 *
 * <p><b>Thread:</b> fired synchronously on the thread initiating the transfer — on Paper
 * the server main thread or console; for Velocity-originated transfers the proxy event
 * thread. Treat it as an arbitrary thread and hop before touching any platform state.
 * <b>Cancellable:</b> yes. <b>Mutability:</b> only the cancellation flag.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public final class ContinuumTransferEvent implements CancellableContinuumEvent {

    private final @NotNull UUID playerId;
    private final @NotNull String fromBackendId;
    private final @Nullable String toBackendId;
    private volatile boolean cancelled;
    private volatile @Nullable String cancelReason;

    /**
     * Creates the event. Constructed by Continuum, not by listeners.
     *
     * @param playerId the player being transferred, never null
     * @param fromBackendId the backend the player is on, never null, never blank
     * @param toBackendId the intended destination backend, or null when not yet decided
     */
    public ContinuumTransferEvent(@NotNull UUID playerId,
                                  @NotNull String fromBackendId,
                                  @Nullable String toBackendId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.fromBackendId = Objects.requireNonNull(fromBackendId, "fromBackendId");
        if (fromBackendId.isBlank()) {
            throw new IllegalArgumentException("fromBackendId must not be blank");
        }
        this.toBackendId = toBackendId;
    }

    @Override
    @NotNull
    public UUID playerId() {
        return playerId;
    }

    /**
     * The backend the player is being transferred from.
     *
     * @return the configured server name, never null, never blank
     */
    @NotNull
    public String fromBackendId() {
        return fromBackendId;
    }

    /**
     * The intended destination backend, when known.
     *
     * @return the destination, or null when not yet decided
     */
    @Nullable
    public String toBackendId() {
        return toBackendId;
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
        if (!(o instanceof ContinuumTransferEvent that)) return false;
        return cancelled == that.cancelled
                && playerId.equals(that.playerId)
                && fromBackendId.equals(that.fromBackendId)
                && Objects.equals(toBackendId, that.toBackendId)
                && Objects.equals(cancelReason, that.cancelReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, fromBackendId, toBackendId, cancelled, cancelReason);
    }

    @Override
    public String toString() {
        return "ContinuumTransferEvent{playerId=" + playerId
                + ", from=" + fromBackendId + ", to=" + toBackendId + ", cancelled=" + cancelled + "}";
    }
}
