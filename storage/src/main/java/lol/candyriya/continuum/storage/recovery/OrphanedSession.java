package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.session.SessionState;

import java.time.Instant;
import java.util.UUID;

/**
 * One session row that looks abandoned: still marked open (any state except
 * {@code RELEASED} / {@code RECOVERY_REQUIRED}), never ended, and whose lease
 * lapsed without a heartbeat from its owner.
 *
 * <p>{@code sessionId} is the storage row id (SQL {@code sessions.session_id},
 * Mongo {@code player_sessions._id}). It is exposed because the recovery flow
 * needs a stable handle for log lines and for the outbox payload.</p>
 */
public record OrphanedSession(long sessionId,
                              UUID playerId,
                              String serverId,
                              long token,
                              SessionState state,
                              Instant startedAt,
                              Instant lastHeartbeat,
                              Instant leaseExpiresAt) {

    public OrphanedSession {
        if (sessionId < 1) {
            throw new IllegalArgumentException("sessionId must be >= 1, got " + sessionId);
        }
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
        if (token < 0) {
            throw new IllegalArgumentException("token must be >= 0, got " + token);
        }
        if (state == null || state == SessionState.RELEASED || state == SessionState.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("state must be an open (non-terminal) session state, got " + state);
        }
        if (startedAt == null || lastHeartbeat == null || leaseExpiresAt == null) {
            throw new IllegalArgumentException("startedAt, lastHeartbeat and leaseExpiresAt must not be null");
        }
    }
}
