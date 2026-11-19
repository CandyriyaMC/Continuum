package lol.candyriya.continuum.velocity.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only inspection of one player's placement, as the proxy can honestly
 * report it.
 *
 * <p>The proxy sees placement from its own events (who is connected to which
 * backend, who was kicked and is awaiting an outcome) but it does not persist
 * session state: storage-backed facts - the fencing token, the last snapshot
 * time, snapshot contents - are owned by the backends and the shared storage,
 * not by this node. Implementations therefore split the view in two: what the
 * registry knows right now, and what storage would know once the storage path
 * is connected on this proxy. Until then, {@link SnapshotAvailability#NOT_CONFIGURED}
 * is reported and the commands say exactly that instead of inventing numbers.</p>
 *
 * <p>Implementations must be thread-safe and non-blocking.</p>
 */
public interface PlayerInspectionService {

    /**
     * Builds the view for {@code playerId}, or empty when nothing is tracked
     * for that player on this node (never connected here, or already gone).
     */
    Optional<PlayerSessionView> inspect(UUID playerId);

    /** Whether storage-backed session data can be reported. */
    enum SnapshotAvailability {
        /** Storage is not connected on this proxy; storage-backed fields report nothing. */
        NOT_CONFIGURED,
        /** Storage is connected; last-snapshot and token details are meaningful. */
        AVAILABLE
    }

    /**
     * One player's placement as far as this node knows it.
     *
     * @param playerId            the player
     * @param backendName         the backend holding the player, or the one a kick was recorded on
     * @param awaitingBackend     true when the player was kicked/lost and no backend outcome is known yet
     * @param awaitingReason      why the player is awaiting, empty when not awaiting
     * @param connectedAt         connect time, or the awaiting marker time when awaiting
     * @param transitionsInWindow reconnects recorded inside the debounce window
     * @param snapshotAvailability whether storage-backed session state can be reported
     */
    record PlayerSessionView(UUID playerId,
                             String backendName,
                             boolean awaitingBackend,
                             String awaitingReason,
                             Instant connectedAt,
                             int transitionsInWindow,
                             SnapshotAvailability snapshotAvailability) {

        public PlayerSessionView {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(backendName, "backendName");
            Objects.requireNonNull(connectedAt, "connectedAt");
            awaitingReason = awaitingReason == null ? "" : awaitingReason;
        }
    }
}
