package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.session.SessionInfo;
import lol.candyriya.continuum.storage.api.SessionRecord;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory view of which fenced session this backend holds per player.
 *
 * <p>The database remains the source of truth; this registry is only the local
 * bookmark of the token this JVM acquired, so saves can be fenced without a
 * read first. It is deliberately not consulted for ownership decisions on the
 * write path — the token check happens inside the atomic database write. A
 * stale entry here surfaces as a {@code StaleToken} result from storage, never
 * as data corruption.</p>
 *
 * <p>Entries are put when a session is acquired (restore path) and removed
 * when it is deliberately released (quit) or when a save comes back stale.</p>
 */
public final class SessionRegistry {

    private final ConcurrentHashMap<UUID, SessionRecord> sessions = new ConcurrentHashMap<>();

    /** The session token for {@code playerId} on this backend, or null when none is held. */
    public SessionRecord get(UUID playerId) {
        return sessions.get(Objects.requireNonNull(playerId, "playerId"));
    }

    /** Remembers the acquired session, replacing any older bookmark. */
    public void put(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        sessions.put(session.playerId(), session);
    }

    /** Drops the bookmark (on release or after a stale write); returns it for the release call. */
    public SessionRecord remove(UUID playerId) {
        return sessions.remove(Objects.requireNonNull(playerId, "playerId"));
    }

    /** Number of held bookmarks; diagnostics. */
    public int size() {
        return sessions.size();
    }

    /** Maps the storage record onto the API view, keeping the stored state. */
    public static SessionInfo toApiInfo(SessionRecord record) {
        Objects.requireNonNull(record, "record");
        return new SessionInfo(record.playerId(), record.serverId(),
                lol.candyriya.continuum.api.session.SessionState.valueOf(record.state().name()),
                record.token(), record.startedAt());
    }

    /** The API view of a session that was just released by this backend. */
    public static SessionInfo releasedInfo(SessionRecord record) {
        Objects.requireNonNull(record, "record");
        return new SessionInfo(record.playerId(), record.serverId(),
                lol.candyriya.continuum.api.session.SessionState.RELEASED,
                record.token(), record.startedAt());
    }
}
