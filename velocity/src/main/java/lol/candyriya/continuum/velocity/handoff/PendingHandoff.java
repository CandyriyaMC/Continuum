package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.session.TransferState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The proxy's per-player view of one in-flight transfer.
 *
 * <p>Immutable; every transition produces a new record with {@code updatedAt}
 * bumped. Lives only in the coordinator's map - the durable story is the
 * backend's snapshot pipeline plus the storage layer, and nothing here is
 * persisted by the proxy.</p>
 *
 * @param playerId      the player being moved; never null
 * @param playerName    username at the time of the request; logs and diagnostics only
 * @param sourceBackend backend the player sat on when the handoff started; null when unknown (initial join)
 * @param targetBackend backend the player is being moved to; never null or blank
 * @param reason        free-form context (e.g. "transfer", "switch", "forced host"); empty when none
 * @param phase         where the transfer is in its lifecycle
 * @param createdAt     when the record was first minted
 * @param updatedAt     when the phase last changed
 */
public record PendingHandoff(UUID playerId,
                             String playerName,
                             String sourceBackend,
                             String targetBackend,
                             String reason,
                             TransferState phase,
                             Instant createdAt,
                             Instant updatedAt) {

    public PendingHandoff {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(targetBackend, "targetBackend");
        if (targetBackend.isBlank()) {
            throw new IllegalArgumentException("targetBackend must not be blank");
        }
        Objects.requireNonNull(reason, "reason (use \"\" for none)");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // reasons end up in logs; keep them single-line even if a backend didn't care
        reason = reason.replace('\n', ' ').replace('\r', ' ');
    }

    /** A copy advanced to {@code next}; {@code updatedAt} is bumped to now. */
    public PendingHandoff transitionTo(TransferState next) {
        HandoffStateMachine.require(phase, next);
        Instant now = Instant.now();
        return new PendingHandoff(playerId, playerName, sourceBackend, targetBackend, reason, next, createdAt, now);
    }

    /** Terminal records admit no further transitions and linger not at all. */
    public boolean terminal() {
        return HandoffStateMachine.isTerminal(phase);
    }
}
