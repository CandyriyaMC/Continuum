package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.storage.api.StatisticsDeltaApplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code stat = stat + ?} accumulate-mutations backed by the same pool as the
 * rest of storage. Never read-modify-write from application memory: the ODKU
 * upsert serializes concurrent increments on the row lock, so parallel
 * backends cannot lose updates. Negative deltas are fine - stat values are
 * signed and may go down (play time spent on a debuff, deaths un-done by an
 * operator, you name it).
 *
 * <p>Each call runs in its own transaction: the upsert takes the row lock and
 * the following read happens while that lock is held, so the returned value is
 * exactly what this call stored - not some newer value a concurrent writer
 * slipped in afterwards.</p>
 *
 * <p>Deliberately NOT built on {@code LAST_INSERT_ID(expr)}: it treats its
 * argument as an unsigned 64-bit value, so a negative delta would wrap around
 * and overflow the signed column.</p>
 */
final class SqlStatisticsDeltaApplier implements StatisticsDeltaApplier {

    private static final Logger log = LoggerFactory.getLogger(SqlStatisticsDeltaApplier.class);

    private static final String SQL_DELTA_UPSERT = """
            INSERT INTO player_stats (player_uuid, stat_key, stat_value, updated_at)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                stat_value = stat_value + VALUES(stat_value),
                updated_at = VALUES(updated_at)
            """;

    private static final String SQL_SELECT_STAT = """
            SELECT stat_value FROM player_stats WHERE player_uuid = ? AND stat_key = ?
            """;

    private final SqlPlayerStorage storage;

    SqlStatisticsDeltaApplier(SqlPlayerStorage storage) {
        this.storage = storage;
    }

    @Override
    public long applyDelta(UUID playerId, String statKey, long delta) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        String key = SqlPlayerStorage.validateStatKey(statKey);
        storage.ensureOpen();
        String uuid = playerId.toString();
        try (Connection connection = storage.poolForStats().getConnection()) {
            connection.setAutoCommit(false);
            try {
                long value = applyDeltaInTransaction(connection, uuid, key, delta);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                resetAndClose(connection);
            }
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to apply delta of " + delta + " to stat " + key + " for " + playerId, e);
        }
    }

    @Override
    public Map<String, Long> applyDeltas(UUID playerId, Map<String, Long> deltas) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Map<String, Long> copy = deltas == null ? Map.of() : new LinkedHashMap<>(deltas);
        for (String key : copy.keySet()) {
            SqlPlayerStorage.validateStatKey(key);
        }
        storage.ensureOpen();
        String uuid = playerId.toString();
        try (Connection connection = storage.poolForStats().getConnection()) {
            connection.setAutoCommit(false);
            Map<String, Long> updated = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, Long> entry : copy.entrySet()) {
                    updated.put(entry.getKey(), applyDeltaInTransaction(connection, uuid, entry.getKey(), entry.getValue()));
                }
                connection.commit();
                return Collections.unmodifiableMap(updated);
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                resetAndClose(connection);
            }
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to apply stat deltas for " + playerId, e);
        }
    }

    private long applyDeltaInTransaction(Connection connection, String uuid, String statKey, long delta) throws SQLException {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try (PreparedStatement statement = connection.prepareStatement(SQL_DELTA_UPSERT)) {
            statement.setString(1, uuid);
            statement.setString(2, statKey);
            statement.setLong(3, delta);
            statement.setObject(4, now);
            statement.executeUpdate();
        }
        // same transaction, row lock held by the upsert: the value read here is
        // exactly the value this call stored
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_STAT)) {
            statement.setString(1, uuid);
            statement.setString(2, statKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("stat row for " + statKey + " vanished inside the delta transaction");
                }
                return rs.getLong(1);
            }
        }
    }

    private static void rollbackQuietly(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
            log.warn("rollback failed: {}", e.getMessage());
        }
    }

    private static void resetAndClose(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("failed to reset autoCommit on pooled connection: {}", e.getMessage());
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("failed to return connection to the pool: {}", e.getMessage());
        }
    }
}
