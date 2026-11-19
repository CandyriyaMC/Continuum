package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.messaging.outbox.OutboxClaimer;
import lol.candyriya.continuum.messaging.outbox.OutboxEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

/**
 * SQL implementation of the relay claim contract, backed by the same
 * {@code outbox} table the storage layer writes.
 *
 * <p>Claim design — a short transaction is held across the publish window
 * instead of a claim marker, because the table has no {@code claimed_at}
 * column and adding one would fork the schema for a single code path:</p>
 * <ol>
 *   <li>{@link #claimBatch(int)} opens one transaction, selects the oldest
 *       claimable rows with {@code FOR UPDATE SKIP LOCKED} (MySQL 8+,
 *       MariaDB 10.6+; on older engines replace it with plain
 *       {@code FOR UPDATE} — correct, just serialized), and keeps the rows
 *       locked and the connection checked out until the batch is fully
 *       resolved;</li>
 *   <li>the relay publishes outside this class; no statement here touches
 *       the network;</li>
 *   <li>{@link #markPublished(Collection)} / {@link #markFailed(Collection, String)}
 *       stage their updates on the held connection; once every claimed row
 *       has an outcome, the transaction commits and the connection is
 *       released. A row the relay never marks simply rolls back when the
 *       claim is abandoned (see below), so nothing stays wedged;</li>
 *   <li>crash anywhere before the outcome is committed: the held
 *       transaction is rolled back (process death closes the socket,
 *       InnoDB releases the locks), rows become claimable again — at-least-once
 *       with idempotent consumers, keyed on the envelope's {@code eventId};</li>
 *   <li>a relay that hangs past {@link #MAX_HOLD} is abandoned by the next
 *       claim/mark call: the transaction is rolled back and the rows are
 *       released. The relay itself caps publishes at 30s, so this only
 *       fires on a truly stuck caller, and it keeps the pool from being
 *       drained by one leaked connection per stuck batch.</li>
 * </ol>
 *
 * <p>Exactly one claim is in flight per claimer instance at a time; the
 * relay is single-threaded, and {@code claimBatch} refuses to stack a second
 * claim on a pending one (it abandons the old one first). One claimer per
 * outbox — do not point two relays at the same table, and do not run the
 * storage-side {@code publishOutbox} drain on another node either: both
 * pipelines consume the same rows, so wire exactly one per deployment.</p>
 */
public final class SqlOutboxClaimer implements OutboxClaimer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqlOutboxClaimer.class);

    /** A claim older than this is considered abandoned by a hung relay. */
    static final Duration MAX_HOLD = Duration.ofMinutes(2);

    private static final String SQL_CLAIM = """
            SELECT id, msg_type, player_uuid, payload_json, created_at, published_at, attempts, last_error
              FROM outbox
             WHERE published_at IS NULL AND attempts < ?
             ORDER BY id
             LIMIT ?
              FOR UPDATE SKIP LOCKED
            """;

    private static final String SQL_MARK_PUBLISHED =
            "UPDATE outbox SET published_at = ? WHERE id = ? AND published_at IS NULL";

    private static final String SQL_MARK_FAILED =
            "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ? AND published_at IS NULL";

    private final DataSource dataSource;
    private final int maxAttempts;

    private volatile InFlight inFlight;

    /** Claims stop at their budget; rows past it are set aside forever. */
    public SqlOutboxClaimer(DataSource dataSource) {
        this(dataSource, OutboxClaimSupport.DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * @param dataSource  pool dedicated to (or shared with) the storage layer
     * @param maxAttempts rows with {@code attempts} at or above this are never claimed
     */
    public SqlOutboxClaimer(DataSource dataSource, int maxAttempts) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.dataSource = dataSource;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public synchronized List<OutboxEvent> claimBatch(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        abandonExpired();
        if (inFlight != null) {
            // a previous batch never got its outcomes recorded; freeing the
            // rows beats wedging them behind a dead relay
            abandonInFlight("claim requested while a previous batch is still unresolved");
        }
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to borrow a connection for the outbox claim", e);
        }
        PreparedStatement statement = null;
        ResultSet rs = null;
        List<Row> rows = new ArrayList<>();
        try {
            connection.setAutoCommit(false);
            statement = connection.prepareStatement(SQL_CLAIM);
            statement.setInt(1, maxAttempts);
            statement.setInt(2, limit);
            rs = statement.executeQuery();
            while (rs.next()) {
                rows.add(readRow(rs));
            }
        } catch (SQLException e) {
            quietlyClose(connection);
            throw StorageException.unavailable("failed to claim outbox batch", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(statement);
        }
        if (rows.isEmpty()) {
            rollbackAndClose(connection);
            return List.of();
        }
        Map<UUID, Long> eventToRow = new ConcurrentHashMap<>();
        Set<Long> pending = new HashSet<>();
        List<OutboxEvent> events = new ArrayList<>(rows.size());
        for (Row row : rows) {
            UUID eventId = OutboxClaimSupport.eventIdFor(row.id());
            eventToRow.put(eventId, row.id());
            pending.add(row.id());
            events.add(OutboxClaimSupport.toEvent(row.id(), row.msgType(), row.playerUuid(),
                    row.payloadJson(), row.attempts(), row.createdAt()));
        }
        inFlight = new InFlight(connection, eventToRow, pending, Instant.now());
        return List.copyOf(events);
    }

    @Override
    public synchronized void markPublished(Collection<UUID> eventIds) {
        applyOutcome(eventIds, true, null);
    }

    @Override
    public synchronized void markFailed(Collection<UUID> eventIds, String reason) {
        applyOutcome(eventIds, false, OutboxClaimSupport.truncate(reason));
    }

    private void applyOutcome(Collection<UUID> eventIds, boolean published, String error) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        abandonExpired();
        InFlight current = inFlight;
        if (current == null) {
            // re-marks after a completed or abandoned claim are expected: the relay may
            // crash between the outcome call and this one. Duplicate publishes are handled
            // downstream by consumer idempotency, so swallowing this loudly is a bug;
            // swallowing it silently is one too - log and move on.
            log.warn("outbox outcomes recorded with no claim in flight (already committed or abandoned); ignoring");
            return;
        }
        Set<UUID> unique = new HashSet<>(eventIds);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Long> rowIds = new ArrayList<>();
        for (UUID eventId : unique) {
            Long rowId = current.eventToRow().get(eventId);
            if (rowId == null) {
                log.warn("outbox outcome references unknown event {} - ignoring it", eventId);
                continue;
            }
            rowIds.add(rowId);
        }
        if (rowIds.isEmpty()) {
            return;
        }
        try {
            if (published) {
                try (PreparedStatement statement = current.connection().prepareStatement(SQL_MARK_PUBLISHED)) {
                    for (Long rowId : rowIds) {
                        statement.setObject(1, now);
                        statement.setLong(2, rowId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            } else {
                try (PreparedStatement statement = current.connection().prepareStatement(SQL_MARK_FAILED)) {
                    for (Long rowId : rowIds) {
                        statement.setString(1, error);
                        statement.setLong(2, rowId);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        } catch (SQLException e) {
            // outcomes stay uncommitted; the rows must not look resolved, so drop the batch
            abandonInFlight("failed to record outbox outcomes");
            throw StorageException.unavailable("failed to record outbox outcomes", e);
        }
        current.pending().removeAll(rowIds);
        if (current.pending().isEmpty()) {
            commitAndClose(current);
            inFlight = null;
        }
    }

    /** Roll back and drop the held claim, if any. Used on shutdown and on stuck relays. */
    public synchronized void abandonInFlight(String why) {
        InFlight current = inFlight;
        inFlight = null;
        if (current == null) {
            return;
        }
        log.warn("abandoning in-flight outbox claim ({}); claimed rows return to the outbox", why);
        rollbackAndClose(current.connection());
    }

    /** Closes the pool connection if one is checked out. Idempotent. */
    @Override
    public void close() {
        abandonInFlight("claimer closed");
    }

    private void abandonExpired() {
        InFlight current = inFlight;
        if (current != null && Duration.between(current.claimedAt(), Instant.now()).compareTo(MAX_HOLD) > 0) {
            abandonInFlight("claim held longer than " + MAX_HOLD + " without outcomes");
        }
    }

    private void commitAndClose(InFlight current) {
        try {
            current.connection().commit();
        } catch (SQLException e) {
            // the relay already published; a failed commit means redelivery, which consumers tolerate
            log.error("failed to commit the outbox outcome transaction; rows will be republished", e);
            rollbackAndClose(current.connection());
            return;
        }
        quietlyClose(current.connection());
    }

    private static void rollbackAndClose(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("failed to roll back the outbox claim; closing the connection anyway", e);
        }
        quietlyClose(connection);
    }

    private static void quietlyClose(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("failed to close the outbox claim connection", e);
        }
    }

    private static void closeQuietly(Statement statement) {
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException e) {
            log.warn("failed to close the outbox claim statement", e);
        }
    }

    private static void closeQuietly(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            log.warn("failed to close the outbox claim result set", e);
        }
    }

    private static Row readRow(ResultSet rs) throws SQLException {
        LocalDateTime created = rs.getObject("created_at", LocalDateTime.class);
        return new Row(
                rs.getLong("id"),
                rs.getString("msg_type"),
                rs.getString("player_uuid"),
                rs.getString("payload_json"),
                rs.getInt("attempts"),
                created.toInstant(ZoneOffset.UTC));
    }

    private record Row(long id, String msgType, String playerUuid, String payloadJson,
                       int attempts, Instant createdAt) {
    }

    private record InFlight(Connection connection, Map<UUID, Long> eventToRow,
                            Set<Long> pending, Instant claimedAt) {
    }
}
