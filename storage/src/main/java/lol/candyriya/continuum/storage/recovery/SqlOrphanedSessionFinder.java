package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * JDBC scan for orphaned sessions against the same {@code sessions} table
 * the storage layer writes. Runs read-only; the marking itself goes through
 * the storage API (fencing-guarded), never through this class.
 */
public final class SqlOrphanedSessionFinder implements OrphanedSessionFinder {

    private static final Logger log = LoggerFactory.getLogger(SqlOrphanedSessionFinder.class);

    private static final String SQL_FIND_ORPHANS = """
            SELECT session_id, player_uuid, server_id, token, state, started_at, last_heartbeat, lease_expires_at
              FROM sessions
             WHERE state NOT IN ('RELEASED', 'RECOVERY_REQUIRED')
               AND ended_at IS NULL
               AND lease_expires_at < ?
             ORDER BY lease_expires_at
             LIMIT ?
            """;

    private final DataSource dataSource;

    public SqlOrphanedSessionFinder(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public List<OrphanedSession> findOrphans(Instant olderThan, int limit) throws StorageException {
        if (olderThan == null) {
            throw new IllegalArgumentException("olderThan must not be null");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        List<OrphanedSession> orphans = new ArrayList<>();
        LocalDateTime horizon = LocalDateTime.ofInstant(olderThan, ZoneOffset.UTC);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_FIND_ORPHANS)) {
            statement.setObject(1, horizon);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String state = rs.getString("state");
                    SessionState parsed;
                    try {
                        parsed = SessionState.valueOf(state);
                    } catch (IllegalArgumentException e) {
                        // unknown state from a newer build: do not guess, leave the row alone
                        log.warn("sessions.session_id {} has unknown state '{}'; skipping", rs.getLong("session_id"), state);
                        continue;
                    }
                    orphans.add(new OrphanedSession(
                            rs.getLong("session_id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("server_id"),
                            rs.getLong("token"),
                            parsed,
                            instant(rs, "started_at"),
                            instant(rs, "last_heartbeat"),
                            instant(rs, "lease_expires_at")));
                }
            }
            return List.copyOf(orphans);
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to scan for orphaned sessions", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value.toInstant(ZoneOffset.UTC);
    }
}
