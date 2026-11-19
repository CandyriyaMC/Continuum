package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.MysqlSettings;
import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotSerializationException;
import lol.candyriya.continuum.common.snapshot.SnapshotSerializer;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.OutboxPublisher;
import lol.candyriya.continuum.storage.api.PlayerSnapshot;
import lol.candyriya.continuum.storage.api.PlayerStats;
import lol.candyriya.continuum.storage.api.PlayerStorage;
import lol.candyriya.continuum.storage.api.SaveOptions;
import lol.candyriya.continuum.storage.api.SaveResult;
import lol.candyriya.continuum.storage.api.SessionRecord;
import lol.candyriya.continuum.storage.api.SnapshotSummary;
import lol.candyriya.continuum.storage.api.StatisticsDeltaApplier;
import lol.candyriya.continuum.storage.api.StorageConfig;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * JDBC implementation of {@link PlayerStorage} for MySQL 8.4/26.x and MariaDB 11.
 *
 * <p>One {@link HikariDataSource} per instance (per configuration). Fail fast
 * at boot: migrations run in {@link #open} and any failure there refuses to
 * serve — half-migrated schemas are never used.</p>
 *
 * <p>Fencing is enforced inside the atomic operations, never in memory:</p>
 * <ul>
 *   <li>issuance: the {@code fencing_counter} row is bumped with
 *       {@code UPDATE ... SET val = LAST_INSERT_ID(val + 1)} in the same transaction as the
 *       session insert — monotonic even with gaps; rollback burns a token, which is fine</li>
 *   <li>writes: guarded {@code UPDATE ... WHERE player_uuid = ? AND fencing_token < ?};
 *       on 0 rows a {@code SELECT ... FOR UPDATE} disambiguates "row absent" (first write)
 *       from "newer session won" (stale write) — and a driver that counts changed rather
 *       than matched rows can only cause a wasted round trip, never a wrong outcome</li>
 *   <li>first write: {@code INSERT ... ON DUPLICATE KEY UPDATE} with an
 *       {@code IF(fencing_token < ...)} guard and {@code LAST_INSERT_ID} outcome detection,
 *       so two racing first writers resolve deterministically with no duplicate-key error</li>
 * </ul>
 *
 * <p>All timestamps are stored as {@code DATETIME(6)} in UTC. All SQL is
 * hand-written prepared statements; nothing is concatenated from caller input.</p>
 */
public final class SqlPlayerStorage implements PlayerStorage {

    private static final Logger log = LoggerFactory.getLogger(SqlPlayerStorage.class);

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Pattern SAFE_DATABASE_NAME = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final int MAX_ERROR_LENGTH = 512;

    // --- SQL statements (kept together on purpose) -------------------------------

    private static final String SQL_GUARDED_UPDATE = """
            UPDATE player_snapshot
               SET schema_version = ?, payload = ?, fencing_token = ?, saved_at = ?,
                   source_backend = ?, reason = ?, modules_json = ?
             WHERE player_uuid = ? AND fencing_token < ?
            """;

    // Locks the row for the rest of the tx (or observes it is absent). FOR UPDATE
    // rather than plain SELECT: under READ_COMMITTED a plain read could observe a
    // version that is already superseded by the time we act on it.
    private static final String SQL_SELECT_TOKEN_FOR_UPDATE = """
            SELECT fencing_token FROM player_snapshot WHERE player_uuid = ? FOR UPDATE
            """;

    // First-write path. LAST_INSERT_ID(expr) threads the winning token out of both
    // branches: the INSERT branch sets it in the VALUES list, the UPDATE branch in the
    // guarded column assignment. After the statement, LAST_INSERT_ID() == our token
    // means "we won", anything else is the stored (newer) token. Deterministic on
    // both drivers, no reliance on update-count semantics.
    private static final String SQL_FIRST_WRITE = """
            INSERT INTO player_snapshot
                (player_uuid, schema_version, payload, fencing_token, saved_at, source_backend, reason, modules_json)
            VALUES (?, ?, ?, LAST_INSERT_ID(?), ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                schema_version = IF(fencing_token < VALUES(fencing_token), VALUES(schema_version), schema_version),
                payload        = IF(fencing_token < VALUES(fencing_token), VALUES(payload), payload),
                saved_at       = IF(fencing_token < VALUES(fencing_token), VALUES(saved_at), saved_at),
                source_backend = IF(fencing_token < VALUES(fencing_token), VALUES(source_backend), source_backend),
                reason         = IF(fencing_token < VALUES(fencing_token), VALUES(reason), reason),
                modules_json   = IF(fencing_token < VALUES(fencing_token), VALUES(modules_json), modules_json),
                fencing_token  = LAST_INSERT_ID(IF(fencing_token < VALUES(fencing_token), VALUES(fencing_token), fencing_token))
            """;

    private static final String SQL_ISSUE_FENCING_TOKEN = """
            UPDATE fencing_counter SET val = LAST_INSERT_ID(val + 1) WHERE id = 1
            """;

    private static final String SQL_UPSERT_PLAYER = """
            INSERT INTO players (player_uuid, created_at, updated_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at)
            """;

    private static final String SQL_SUPERSEDE_SESSIONS = """
            UPDATE sessions
               SET state = 'RELEASED', ended_at = ?, close_reason = 'superseded'
             WHERE player_uuid = ? AND token < ? AND state <> 'RELEASED'
            """;

    private static final String SQL_INSERT_SESSION = """
            INSERT INTO sessions
                (player_uuid, server_id, token, state, started_at, last_heartbeat, lease_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_SELECT_SESSION_BY_PLAYER = """
            SELECT session_id, player_uuid, server_id, token, state, started_at, last_heartbeat, lease_expires_at, ended_at
              FROM sessions
             WHERE player_uuid = ?
             ORDER BY token DESC
             LIMIT 1
            """;

    private static final String SQL_SELECT_SESSION_BY_TOKEN = """
            SELECT session_id, player_uuid, server_id, token, state, started_at, last_heartbeat, lease_expires_at, ended_at
              FROM sessions
             WHERE player_uuid = ? AND token = ?
            """;

    private static final String SQL_TRANSITION_SESSION = """
            UPDATE sessions SET state = ?
             WHERE player_uuid = ? AND token = ? AND state <> 'RELEASED'
            """;

    private static final String SQL_HEARTBEAT_SESSION = """
            UPDATE sessions SET last_heartbeat = ?, lease_expires_at = ?
             WHERE player_uuid = ? AND token = ? AND state <> 'RELEASED'
            """;

    private static final String SQL_RELEASE_SESSION = """
            UPDATE sessions SET state = 'RELEASED', ended_at = ?, close_reason = 'released'
             WHERE player_uuid = ? AND token = ? AND state <> 'RELEASED'
            """;

    private static final String SQL_LOAD_SNAPSHOT = """
            SELECT schema_version, payload, fencing_token, saved_at
              FROM player_snapshot WHERE player_uuid = ?
            """;

    private static final String SQL_INSERT_HISTORY = """
            INSERT INTO player_snapshot_history
                (player_uuid, schema_version, payload, fencing_token, saved_at, source_backend, reason, modules_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // NOT IN over a derived table: MySQL forbids referencing the same table
    // directly in a subquery of a DELETE.
    private static final String SQL_PRUNE_HISTORY = """
            DELETE FROM player_snapshot_history
             WHERE player_uuid = ? AND snapshot_id NOT IN (
                    SELECT snapshot_id FROM (
                        SELECT snapshot_id FROM player_snapshot_history
                         WHERE player_uuid = ?
                         ORDER BY snapshot_id DESC
                         LIMIT ?
                    ) AS keep_rows
             )
            """;

    private static final String SQL_LIST_HISTORY = """
            SELECT snapshot_id, player_uuid, schema_version, fencing_token, saved_at, source_backend, reason, modules_json
              FROM player_snapshot_history
             WHERE player_uuid = ?
             ORDER BY snapshot_id DESC
             LIMIT ?
            """;

    private static final String SQL_LOAD_SNAPSHOT_BY_ID = """
            SELECT player_uuid, schema_version, payload, fencing_token, saved_at
              FROM player_snapshot_history
             WHERE snapshot_id = ?
            """;

    private static final String SQL_READ_STATS = """
            SELECT stat_key, stat_value FROM player_stats WHERE player_uuid = ?
            """;

    private static final String SQL_DELETE_STATS = "DELETE FROM player_stats WHERE player_uuid = ?";

    private static final String SQL_INSERT_STAT = """
            INSERT INTO player_stats (player_uuid, stat_key, stat_value, updated_at) VALUES (?, ?, ?, ?)
            """;

    private static final String SQL_INSERT_OUTBOX = """
            INSERT INTO outbox (msg_type, player_uuid, payload_json, created_at, attempts) VALUES (?, ?, ?, ?, 0)
            """;

    private static final String SQL_POLL_OUTBOX = """
            SELECT id, msg_type, player_uuid, payload_json, created_at, published_at, attempts, last_error
              FROM outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT ?
            """;

    // The real claim: unclaimed rows only, walk past locked ones. SKIP LOCKED is
    // the whole point — a second publisher never queues behind a slow first.
    private static final String SQL_CLAIM_OUTBOX = """
            SELECT id, msg_type, player_uuid, payload_json, created_at, published_at, attempts, last_error
              FROM outbox
             WHERE published_at IS NULL AND attempts < ?
             ORDER BY id
             LIMIT ?
             FOR UPDATE SKIP LOCKED
            """;

    private static final String SQL_MARK_PUBLISHED = "UPDATE outbox SET published_at = ? WHERE id = ? AND published_at IS NULL";

    private static final String SQL_MARK_FAILED = "UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ? AND published_at IS NULL";

    // ------------------------------------------------------------------------

    private final HikariDataSource dataSource;
    private final SnapshotSerializer serializer;
    private final StorageConfig config;
    private final SqlStatisticsDeltaApplier statistics = new SqlStatisticsDeltaApplier(this);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private SqlPlayerStorage(HikariDataSource dataSource, SnapshotSerializer serializer, StorageConfig config) {
        this.dataSource = dataSource;
        this.serializer = serializer;
        this.config = config;
    }

    /**
     * Builds a storage instance: pool, then migrations, fail fast.
     *
     * @param settings     connection settings; {@code database} must be a bare catalog name
     * @param engine       MYSQL or MARIADB; MONGODB is rejected
     * @param serialization how snapshots are (de)serialized on disk
     * @param storageConfig history keep, session lease, leak detection (dev)
     * @throws lol.candyriya.continuum.storage.api.SchemaMigrationException when migrations cannot be applied
     * @throws IllegalArgumentException for nonsensical settings
     */
    public static SqlPlayerStorage open(MysqlSettings settings,
                                        DatabaseType engine,
                                        SerializationConfig serialization,
                                        StorageConfig storageConfig) {
        Objects.requireNonNull(settings, "settings must not be null");
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(serialization, "serialization must not be null");
        Objects.requireNonNull(storageConfig, "storageConfig must not be null");
        if (engine == DatabaseType.MONGODB) {
            throw new IllegalArgumentException("SqlPlayerStorage requires MYSQL or MARIADB, got MONGODB");
        }
        String url = jdbcUrl(settings, engine);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(settings.username() == null ? "" : settings.username());
        hikari.setPassword(settings.password() == null ? "" : settings.password());
        hikari.setPoolName("ContinuumSqlPool");
        int maxPool = Math.max(1, settings.pool().maximumPoolSize());
        hikari.setMaximumPoolSize(maxPool);
        // minimumIdle deliberately NOT set: Hikari defaults it to maximumPoolSize,
        // which is the fixed-size pool this project wants — a burst of borrows must
        // never queue behind a lazy ramp-up. The config's minimumIdle is logged and ignored.
        int configuredIdle = settings.pool().minimumIdle();
        if (configuredIdle != maxPool) {
            log.info("minimumIdle={} ignored: using a fixed pool of {} (minimumIdle == maximumPoolSize)", configuredIdle, maxPool);
        }
        long connectionTimeoutMs = settings.pool().connectionTimeout().toMillis();
        if (connectionTimeoutMs < 250 || connectionTimeoutMs > 30_000) {
            throw new IllegalArgumentException("connectionTimeout must be between 250ms and 30s, got " + settings.pool().connectionTimeout());
        }
        hikari.setConnectionTimeout(connectionTimeoutMs);
        long maxLifetimeMs = settings.pool().maxLifetime().toMillis();
        if (maxLifetimeMs < 1_000) {
            throw new IllegalArgumentException("maxLifetime must be at least 1s, got " + settings.pool().maxLifetime());
        }
        hikari.setMaxLifetime(maxLifetimeMs);
        hikari.setKeepaliveTime(60_000);
        // dev convenience only; in production it would spam a warning on every long borrow
        hikari.setLeakDetectionThreshold(storageConfig.leakDetection() ? 10_000 : 0);
        hikari.setInitializationFailTimeout(1);
        hikari.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        hikari.setRegisterMbeans(true);
        // connectionTestQuery deliberately NOT set — Hikari uses JDBC4 isValid()
        // autoCommit deliberately NOT set — callers manage transactions explicitly

        HikariDataSource dataSource = new HikariDataSource(hikari);
        try {
            MigrationRunner.run(dataSource);
        } catch (RuntimeException e) {
            // fail fast: a broken schema is not servable, and the pool must not linger
            dataSource.close();
            throw e;
        }
        log.info("SQL storage ready (engine={}, pool={}, keepLast={})", engine, maxPool, storageConfig.historyKeepLast());
        return new SqlPlayerStorage(dataSource, new SnapshotSerializer(serialization), storageConfig);
    }

    static String jdbcUrl(MysqlSettings settings, DatabaseType engine) {
        String host = settings.host();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("database host must not be blank");
        }
        if (host.contains("/") || host.contains("?") || host.contains("#") || host.contains(" ")) {
            throw new IllegalArgumentException("database host contains illegal characters: " + host);
        }
        int port = settings.port();
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("database port out of range: " + port);
        }
        String database = settings.database();
        if (database == null || !SAFE_DATABASE_NAME.matcher(database).matches()) {
            throw new IllegalArgumentException("database name must be a bare catalog name matching [A-Za-z0-9_$-]{1,64}, got: " + database);
        }
        String scheme = engine == DatabaseType.MARIADB ? "jdbc:mariadb" : "jdbc:mysql";
        // Only cross-engine properties, verified against both drivers' property tables:
        // cachePrepStmts/prepStmtCacheSize are driver-side on both; connectionCollation
        // utf8mb4_unicode_ci is the portable one (0900_ai_ci does not exist on MariaDB).
        // (useUnicode / characterEncoding do not exist in both drivers - left out on purpose.)
        return scheme + "://" + host + ":" + port + "/" + database
                + "?cachePrepStmts=true&prepStmtCacheSize=250"
                + "&connectionCollation=utf8mb4_unicode_ci"
                + "&tcpKeepAlive=true";
    }

    // --- internals used by the stats applier --------------------------------------

    HikariDataSource poolForStats() {
        ensureOpen();
        return dataSource;
    }

    void ensureOpen() {
        if (closed.get()) {
            throw StorageException.unavailable("storage is closed", null);
        }
    }

    // --- snapshots -----------------------------------------------------------------

    @Override
    public Optional<PlayerSnapshot> load(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        ensureOpen();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_LOAD_SNAPSHOT)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                SnapshotEnvelope envelope = readEnvelope(rs.getBytes("payload"), playerId);
                return Optional.of(new PlayerSnapshot(envelope, rs.getLong("fencing_token"), instant(rs, "saved_at")));
            }
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to load snapshot for " + playerId, e);
        }
    }

    @Override
    public SaveResult save(PlayerSnapshot snapshot, long fencingToken, SaveOptions options) throws StorageException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        SaveOptions opts = options == null ? SaveOptions.defaults() : options;
        ensureOpen();

        byte[] payload = serializer.write(snapshot.envelope());
        String uuid = snapshot.playerId().toString();
        LocalDateTime now = LocalDateTime.now(UTC);
        String modulesJson = ModulesJson.encode(snapshot.moduleIds());

        try {
            return inTransaction(connection -> {
                // Path A (happy path): one guarded UPDATE, no prior SELECT.
                // 1 row → we won. 0 → row absent, or a newer token holds it, or a driver
                // counted changed-rather-than-matched rows on an identical rewrite —
                // disambiguate() sorts all of those out, so a wrong 0 here can never
                // produce a wrong result, only an extra round trip.
                int updated = guardedUpdate(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
                if (updated == 1) {
                    return won(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
                }
                Long current = selectTokenForUpdate(connection, uuid);
                if (current != null && current >= fencingToken) {
                    // a newer (or equal) session owns the row — abstain completely
                    return SaveResult.staleToken(current);
                }
                if (current == null) {
                    // first write; racing first writers resolve inside the statement via LAST_INSERT_ID
                    long detected = firstWrite(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
                    return detected == fencingToken
                            ? won(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson)
                            : SaveResult.staleToken(detected);
                }
                // path A matched 0 yet the locked row shows an older token: whatever the
                // driver counted, the state is stable under our lock — just write it
                guardedUpdate(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
                return won(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to save snapshot for " + snapshot.playerId(), e);
        }
    }

    private SaveResult won(Connection connection, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                           byte[] payload, String uuid, LocalDateTime now, String modulesJson) throws SQLException {
        long historyId = insertHistory(connection, snapshot, fencingToken, opts, payload, uuid, now, modulesJson);
        pruneHistory(connection, uuid);
        upsertPlayer(connection, uuid, now);
        for (SaveOptions.OutboxDraft draft : opts.outbox()) {
            insertOutbox(connection, draft.msgType(), snapshot.playerId(), draft.payloadJson(), now);
        }
        return SaveResult.saved(historyId);
    }

    private int guardedUpdate(Connection connection, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                              byte[] payload, String uuid, LocalDateTime now, String modulesJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_GUARDED_UPDATE)) {
            statement.setInt(1, snapshot.schemaVersion());
            statement.setBytes(2, payload);
            statement.setLong(3, fencingToken);
            statement.setObject(4, now);
            statement.setString(5, opts.sourceBackend());
            statement.setString(6, opts.reason());
            statement.setString(7, modulesJson);
            statement.setString(8, uuid);
            statement.setLong(9, fencingToken);
            return statement.executeUpdate();
        }
    }

    private Long selectTokenForUpdate(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_TOKEN_FOR_UPDATE)) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long firstWrite(Connection connection, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                            byte[] payload, String uuid, LocalDateTime now, String modulesJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_FIRST_WRITE)) {
            statement.setString(1, uuid);
            statement.setInt(2, snapshot.schemaVersion());
            statement.setBytes(3, payload);
            statement.setLong(4, fencingToken);
            statement.setObject(5, now);
            statement.setString(6, opts.sourceBackend());
            statement.setString(7, opts.reason());
            statement.setString(8, modulesJson);
            statement.executeUpdate();
        }
        // MUST be the same pooled connection as the ODKU statement — LAST_INSERT_ID is session-scoped
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long insertHistory(Connection connection, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                               byte[] payload, String uuid, LocalDateTime now, String modulesJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_HISTORY, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, uuid);
            statement.setInt(2, snapshot.schemaVersion());
            statement.setBytes(3, payload);
            statement.setLong(4, fencingToken);
            statement.setObject(5, now);
            statement.setString(6, opts.sourceBackend());
            statement.setString(7, opts.reason());
            statement.setString(8, modulesJson);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("history insert did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private void pruneHistory(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_PRUNE_HISTORY)) {
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            statement.setInt(3, config.historyKeepLast());
            statement.executeUpdate();
        }
    }

    private void upsertPlayer(Connection connection, String uuid, LocalDateTime now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_UPSERT_PLAYER)) {
            statement.setString(1, uuid);
            statement.setObject(2, now);
            statement.setObject(3, now);
            statement.executeUpdate();
        }
    }

    // --- sessions ------------------------------------------------------------------

    @Override
    public SessionRecord acquireSession(UUID playerId, String serverId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank");
        }
        if (serverId.length() > 64) {
            throw new IllegalArgumentException("serverId must be at most 64 characters, got " + serverId.length());
        }
        ensureOpen();
        String uuid = playerId.toString();
        Instant now = Instant.now();
        LocalDateTime dbNow = toDb(now);
        LocalDateTime leaseUntil = toDb(now.plus(config.sessionLease()));
        try {
            return inTransaction(connection -> {
                // issue a globally monotonic token; rollback burning a token is fine (gaps are ok)
                long token = issueFencingToken(connection);
                upsertPlayer(connection, uuid, dbNow);
                // ownership claim: anything still marked open for this player yields
                try (PreparedStatement statement = connection.prepareStatement(SQL_SUPERSEDE_SESSIONS)) {
                    statement.setObject(1, dbNow);
                    statement.setString(2, uuid);
                    statement.setLong(3, token);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_SESSION)) {
                    statement.setString(1, uuid);
                    statement.setString(2, serverId);
                    statement.setLong(3, token);
                    statement.setString(4, SessionState.CONNECTING.name());
                    statement.setObject(5, dbNow);
                    statement.setObject(6, dbNow);
                    statement.setObject(7, leaseUntil);
                    statement.executeUpdate();
                }
                long acquired = token;
                // read back what the database actually stored — no hand-built records from locals
                return findSessionByToken(connection, uuid, acquired)
                        .orElseThrow(() -> new SQLException("session with token " + acquired + " vanished right after insert"));
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to acquire session for " + playerId, e);
        }
    }

    @Override
    public Optional<SessionRecord> transitionSession(UUID playerId, long fencingToken, SessionState state) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        if (state == SessionState.CONNECTING || state == SessionState.RELEASED) {
            throw new IllegalArgumentException("transition target must not be CONNECTING or RELEASED; release via releaseSession instead");
        }
        ensureOpen();
        String uuid = playerId.toString();
        try {
            return inTransaction(connection -> {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(SQL_TRANSITION_SESSION)) {
                    statement.setString(1, state.name());
                    statement.setString(2, uuid);
                    statement.setLong(3, fencingToken);
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    return Optional.empty();
                }
                return findSessionByToken(connection, uuid, fencingToken);
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to transition session for " + playerId, e);
        }
    }

    @Override
    public Optional<SessionRecord> heartbeatSession(UUID playerId, long fencingToken) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        ensureOpen();
        String uuid = playerId.toString();
        Instant now = Instant.now();
        LocalDateTime dbNow = toDb(now);
        LocalDateTime leaseUntil = toDb(now.plus(config.sessionLease()));
        try {
            return inTransaction(connection -> {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(SQL_HEARTBEAT_SESSION)) {
                    statement.setObject(1, dbNow);
                    statement.setObject(2, leaseUntil);
                    statement.setString(3, uuid);
                    statement.setLong(4, fencingToken);
                    updated = statement.executeUpdate();
                }
                if (updated == 0) {
                    return Optional.empty();
                }
                return findSessionByToken(connection, uuid, fencingToken);
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to heartbeat session for " + playerId, e);
        }
    }

    @Override
    public boolean releaseSession(UUID playerId, long fencingToken) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        ensureOpen();
        String uuid = playerId.toString();
        LocalDateTime now = LocalDateTime.now(UTC);
        try {
            return inTransaction(connection -> {
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(SQL_RELEASE_SESSION)) {
                    statement.setObject(1, now);
                    statement.setString(2, uuid);
                    statement.setLong(3, fencingToken);
                    updated = statement.executeUpdate();
                }
                return updated == 1;
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to release session for " + playerId, e);
        }
    }

    @Override
    public Optional<SessionRecord> getSession(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        ensureOpen();
        String uuid = playerId.toString();
        try (Connection connection = dataSource.getConnection()) {
            return findSessionByPlayer(connection, uuid);
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to read session for " + playerId, e);
        }
    }

    private long issueFencingToken(Connection connection) throws SQLException {
        // both statements on the SAME pooled connection — LAST_INSERT_ID is session-scoped
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(SQL_ISSUE_FENCING_TOKEN)) {
            updated = statement.executeUpdate();
        }
        if (updated != 1) {
            throw new SQLException("fencing_counter row (id=1) is missing — schema is broken; refusing to issue tokens");
        }
        return lastInsertId(connection);
    }

    private long lastInsertId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Optional<SessionRecord> findSessionByToken(Connection connection, String uuid, long token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_SESSION_BY_TOKEN)) {
            statement.setString(1, uuid);
            statement.setLong(2, token);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        }
    }

    private Optional<SessionRecord> findSessionByPlayer(Connection connection, String uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_SESSION_BY_PLAYER)) {
            statement.setString(1, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readSession(rs)) : Optional.empty();
            }
        }
    }

    private static SessionRecord readSession(ResultSet rs) throws SQLException {
        LocalDateTime ended = rs.getObject("ended_at", LocalDateTime.class);
        String rawState = rs.getString("state");
        SessionState state;
        try {
            state = SessionState.valueOf(rawState);
        } catch (IllegalArgumentException e) {
            throw new SQLException("stored session state '" + rawState + "' is not a known SessionState", e);
        }
        return new SessionRecord(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("session_id"),
                rs.getString("server_id"),
                rs.getLong("token"),
                state,
                instant(rs, "started_at"),
                instant(rs, "last_heartbeat"),
                instant(rs, "lease_expires_at"),
                ended == null ? null : ended.toInstant(UTC));
    }

    // --- history -------------------------------------------------------------------

    @Override
    public List<SnapshotSummary> listHistory(UUID playerId, int limit) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        ensureOpen();
        String uuid = playerId.toString();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_LIST_HISTORY)) {
            statement.setString(1, uuid);
            statement.setInt(2, limit);
            List<SnapshotSummary> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new SnapshotSummary(
                            rs.getLong("snapshot_id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getInt("schema_version"),
                            rs.getLong("fencing_token"),
                            instant(rs, "saved_at"),
                            rs.getString("reason"),
                            rs.getString("source_backend"),
                            ModulesJson.decode(rs.getString("modules_json"))));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException | IllegalArgumentException e) {
            throw StorageException.unavailable("failed to list history for " + playerId, e);
        }
    }

    @Override
    public Optional<PlayerSnapshot> loadSnapshot(long snapshotId) throws StorageException {
        if (snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1, got " + snapshotId);
        }
        ensureOpen();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_LOAD_SNAPSHOT_BY_ID)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID playerId = UUID.fromString(rs.getString("player_uuid"));
                SnapshotEnvelope envelope = readEnvelope(rs.getBytes("payload"), playerId);
                return Optional.of(new PlayerSnapshot(envelope, rs.getLong("fencing_token"), instant(rs, "saved_at")));
            }
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to load snapshot " + snapshotId, e);
        }
    }

    // --- statistics ----------------------------------------------------------------

    @Override
    public PlayerStats readStats(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        ensureOpen();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_READ_STATS)) {
            statement.setString(1, playerId.toString());
            Map<String, Long> values = new LinkedHashMap<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.put(rs.getString("stat_key"), rs.getLong("stat_value"));
                }
            }
            return new PlayerStats(playerId, values);
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to read stats for " + playerId, e);
        }
    }

    @Override
    public void writeStatsBaseline(UUID playerId, Map<String, Long> stats) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Map<String, Long> copy = stats == null ? Map.of() : Map.copyOf(stats);
        for (String key : copy.keySet()) {
            validateStatKey(key);
        }
        ensureOpen();
        String uuid = playerId.toString();
        LocalDateTime now = LocalDateTime.now(UTC);
        try {
            inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(SQL_DELETE_STATS)) {
                    statement.setString(1, uuid);
                    statement.executeUpdate();
                }
                if (!copy.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_STAT)) {
                        for (Map.Entry<String, Long> entry : copy.entrySet()) {
                            statement.setString(1, uuid);
                            statement.setString(2, entry.getKey());
                            statement.setLong(3, entry.getValue());
                            statement.setObject(4, now);
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to write stats baseline for " + playerId, e);
        }
    }

    @Override
    public StatisticsDeltaApplier statistics() {
        return statistics;
    }

    static String validateStatKey(String statKey) {
        if (statKey == null || statKey.isBlank()) {
            throw new IllegalArgumentException("statKey must not be blank");
        }
        if (statKey.length() > 64) {
            throw new IllegalArgumentException("statKey must be at most 64 characters, got " + statKey.length());
        }
        return statKey;
    }

    // --- outbox ---------------------------------------------------------------------

    @Override
    public OutboxEntry appendOutbox(String msgType, UUID playerId, String payloadJson) throws StorageException {
        if (msgType == null || msgType.isBlank()) {
            throw new IllegalArgumentException("msgType must not be blank");
        }
        if (msgType.length() > 64) {
            throw new IllegalArgumentException("msgType must be at most 64 characters, got " + msgType.length());
        }
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        ensureOpen();
        LocalDateTime now = LocalDateTime.now(UTC);
        try {
            return inTransaction(connection -> {
                long id = insertOutbox(connection, msgType, playerId, payloadJson, now);
                return new OutboxEntry(id, msgType, playerId, payloadJson, now.toInstant(UTC), null, 0, null);
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to append outbox event " + msgType, e);
        }
    }

    @Override
    public List<OutboxEntry> pollOutbox(int limit) throws StorageException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        ensureOpen();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_POLL_OUTBOX)) {
            statement.setInt(1, limit);
            List<OutboxEntry> entries = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(readOutbox(rs));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to poll outbox", e);
        }
    }

    @Override
    public int publishOutbox(int limit, int maxAttempts, OutboxPublisher publisher) throws StorageException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        Objects.requireNonNull(publisher, "publisher must not be null");
        ensureOpen();
        try {
            return inTransaction(connection -> {
                List<OutboxEntry> claimed = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(SQL_CLAIM_OUTBOX)) {
                    statement.setInt(1, maxAttempts);
                    statement.setInt(2, limit);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            claimed.add(readOutbox(rs));
                        }
                    }
                }
                int published = 0;
                for (OutboxEntry entry : claimed) {
                    boolean ok;
                    String error = null;
                    try {
                        ok = publisher.publish(entry);
                    } catch (Exception e) {
                        ok = false;
                        error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    }
                    if (ok) {
                        markPublished(connection, List.of(entry.id()));
                        published++;
                    } else {
                        markFailed(connection, entry.id(), error == null ? "publisher reported failure" : error);
                    }
                }
                return published;
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to publish outbox batch", e);
        }
    }

    @Override
    public void markOutboxPublished(Collection<Long> ids) throws StorageException {
        Objects.requireNonNull(ids, "ids must not be null");
        ensureOpen();
        List<Long> copy = List.copyOf(ids);
        if (copy.isEmpty()) {
            return;
        }
        try {
            inTransaction(connection -> {
                markPublished(connection, copy);
                return null;
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to mark " + copy.size() + " outbox entries published", e);
        }
    }

    @Override
    public void markOutboxFailed(long id, String error) throws StorageException {
        if (id < 1) {
            throw new IllegalArgumentException("id must be >= 1, got " + id);
        }
        ensureOpen();
        try {
            inTransaction(connection -> {
                markFailed(connection, id, error);
                return null;
            });
        } catch (SQLException e) {
            throw StorageException.unavailable("failed to mark outbox entry " + id + " as failed", e);
        }
    }

    private long insertOutbox(Connection connection, String msgType, UUID playerId, String payloadJson, LocalDateTime now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_OUTBOX, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, msgType);
            statement.setString(2, playerId.toString());
            statement.setString(3, payloadJson);
            statement.setObject(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("outbox insert did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private void markPublished(Connection connection, Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(UTC);
        try (PreparedStatement statement = connection.prepareStatement(SQL_MARK_PUBLISHED)) {
            for (Long id : ids) {
                statement.setObject(1, now);
                statement.setLong(2, id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void markFailed(Connection connection, long id, String error) throws SQLException {
        String message = error == null || error.isBlank() ? "unknown publisher error" : error;
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL_MARK_FAILED)) {
            statement.setString(1, message);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private static OutboxEntry readOutbox(ResultSet rs) throws SQLException {
        LocalDateTime published = rs.getObject("published_at", LocalDateTime.class);
        return new OutboxEntry(
                rs.getLong("id"),
                rs.getString("msg_type"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("payload_json"),
                instant(rs, "created_at"),
                published == null ? null : published.toInstant(UTC),
                rs.getInt("attempts"),
                rs.getString("last_error"));
    }

    // --- helpers ---------------------------------------------------------------------

    private SnapshotEnvelope readEnvelope(byte[] payload, UUID expectedPlayer) throws StorageException {
        if (payload == null || payload.length == 0) {
            throw StorageException.unavailable("stored snapshot payload is empty", null);
        }
        try {
            return serializer.read(payload);
        } catch (SnapshotSerializationException e) {
            throw StorageException.unavailable("stored snapshot for " + expectedPlayer + " could not be decoded", e);
        }
    }

    private static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        if (value == null) {
            throw new SQLException("column " + column + " unexpectedly NULL");
        }
        return value.toInstant(UTC);
    }

    /** Runs {@code work} inside a transaction: commit on success, rollback (with cause attached) on any failure. */
    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            rollback(connection, e);
            throw e;
        } finally {
            closeQuietly(connection);
        }
    }

    private static void rollback(Connection connection, Exception cause) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
            log.warn("rollback failed: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        // reset before returning to the pool: leaving autoCommit=false on a pooled
        // connection is asking for surprises for the next borrower
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("closing SQL storage pool");
            dataSource.close();
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
