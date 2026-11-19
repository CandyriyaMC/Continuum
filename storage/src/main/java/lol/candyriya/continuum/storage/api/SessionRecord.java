package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.session.SessionState;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A session row: who owns a player, where, and under which fencing token.
 *
 * <p>{@link #state()} is the stored lifecycle state; {@link #endedAt()} is null
 * while the session is owned. A session whose lease expired is not automatically
 * released — the lease is advisory; only fencing tokens prevent stale writes.</p>
 *
 * @param playerId       the player this session owns, never null
 * @param sessionId      database id of the session row, {@code >= 1}
 * @param serverId       the backend that holds the session, never null or blank
 * @param token          the fencing token issued for this session, monotonically increasing
 *                       across the whole database; never negative
 * @param state          stored lifecycle state, never null
 * @param startedAt      when the session was created (UTC), never null
 * @param lastHeartbeat  when the owner last renewed the lease (UTC), never null
 * @param leaseExpiresAt when the lease lapses (UTC); afterwards only fencing still protects writes
 * @param endedAt        when the session closed (UTC), or null while owned
 */
public record SessionRecord(UUID playerId,
                            long sessionId,
                            String serverId,
                            long token,
                            SessionState state,
                            Instant startedAt,
                            Instant lastHeartbeat,
                            Instant leaseExpiresAt,
                            Instant endedAt) {

    public SessionRecord {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (sessionId < 1) {
            throw new IllegalArgumentException("sessionId must be >= 1, got " + sessionId);
        }
        Objects.requireNonNull(serverId, "serverId must not be null");
        if (serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
        if (serverId.length() > 64) {
            throw new IllegalArgumentException("serverId must be at most 64 characters, got " + serverId.length());
        }
        if (token < 0) {
            throw new IllegalArgumentException("token must be >= 0, got " + token);
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        // endedAt may be null — that's the whole point of the field
    }

    /** True while the session owns the player (not released, not marked for recovery). */
    public boolean owned() {
        return state != SessionState.RELEASED && state != SessionState.RECOVERY_REQUIRED;
    }
}
