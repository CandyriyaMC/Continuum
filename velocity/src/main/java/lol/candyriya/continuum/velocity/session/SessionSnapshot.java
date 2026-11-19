package lol.candyriya.continuum.velocity.session;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only view of one player's session position as the proxy sees it.
 *
 * @param playerId           the player
 * @param backendName        the backend the player is on (or was kicked from, when awaiting)
 * @param connectedAt        when the current backend connection was established; for a kicked
 *                           player awaiting a backend decision, when the kick was recorded
 * @param transitionsInWindow transitions recorded inside the reconnect debounce window
 * @param awaitingBackend    true when a loss/kick signal is pending a backend outcome
 * @param awaitingReason     why the player is awaiting (plain text), empty when not awaiting
 */
public record SessionSnapshot(UUID playerId,
                              String backendName,
                              Instant connectedAt,
                              int transitionsInWindow,
                              boolean awaitingBackend,
                              String awaitingReason) {

    public SessionSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(connectedAt, "connectedAt");
        awaitingReason = awaitingReason == null ? "" : awaitingReason;
    }
}
