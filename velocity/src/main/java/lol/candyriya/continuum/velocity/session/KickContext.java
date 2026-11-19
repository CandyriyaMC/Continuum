package lol.candyriya.continuum.velocity.session;

import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the policy gets told when a player is bounced off a backend.
 *
 * @param playerId   player that was kicked
 * @param backendName the backend that kicked them
 * @param kickReason the reason the backend gave, when it gave one (may be null)
 * @param kickedAt   when the proxy observed the kick
 */
public record KickContext(UUID playerId, String backendName, Component kickReason, Instant kickedAt) {

    public KickContext {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(kickedAt, "kickedAt");
    }
}
