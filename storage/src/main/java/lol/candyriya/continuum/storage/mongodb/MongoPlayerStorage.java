package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.common.config.MongoSettings;
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

import com.mongodb.MongoException;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.TransactionOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import org.bson.conversions.Bson;
import org.bson.BsonBinarySubType;
import org.bson.Document;
import org.bson.types.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.or;

/**
 * MongoDB implementation of {@link PlayerStorage} against MongoDB 8.0 (Atlas M0
 * for small networks, a self-hosted replica set for large ones) using the
 * official sync driver.
 *
 * <p>One {@link MongoClient} per instance (per configuration). Everything
 * multi-document runs inside {@link ClientSession#withTransaction} with
 * READ/WRITE concern MAJORITY. The transaction callbacks are side-effect free
 * with respect to the outside world: the driver is free to re-run a callback
 * that fails with a transient error, so a callback may only talk to MongoDB
 * and must tolerate being executed more than once (all effects are inside the
 * transaction that either commits completely or not at all).</p>
 *
 * <p>Fencing is enforced inside the atomic operations, never in memory:</p>
 * <ul>
 *   <li>issuance: a {@code snapshot_counters} document
 *       ({@code {_id: "fence", value}}) is bumped with
 *       {@code $inc} inside the same transaction as the session insert —
 *       findOneAndUpdate with upsert + ReturnDocument.AFTER, atomic; rollback
 *       of the transaction rolls back the increment, so no token is ever
 *       burned by an aborted acquire;</li>
 *   <li>writes: guarded
 *       {@code findOneAndUpdate({_id: playerUuid, fencingToken: {$lt: fence}}, ...)};
 *       null means "no match", which is disambiguated by a follow-up read
 *       inside the same transaction (absent → first write via a guarded
 *       upsert; present with a newer/equal token → {@link SaveResult.StaleToken});</li>
 *   <li>first write: guarded upsert with {@code $setOnInsert}-free equality
 *       filter (the {@code _id} equality supplies the inserted id). A loser of
 *       the first-write race gets a write conflict (transient, the driver
 *       retries the whole transaction, and the retried attempt observes the
 *       winner) or a non-transient duplicate-key error, which
 *       {@link #save} translates into one bounded retry of the whole save —
 *       deterministic convergence on the max token either way;</li>
 *   <li>unique indexes make the first write safe: the loser of two inserts
 *       of the same {@code _id} is rejected by the {@code _id} unique index.</li>
 * </ul>
 *
 * <p>Layout (collections):</p>
 * <ul>
 *   <li>{@code players} — {@code {_id: playerUuid, createdAt, updatedAt}}</li>
 *   <li>{@code player_snapshots} — {@code {_id: playerUuid, schemaVersion,
 *       payload: Binary, fencingToken, savedAt, sourceBackend, reason, modules: [String]}}</li>
 *   <li>{@code player_snapshot_history} — append-only, {@code {_id: long,
 *       playerUuid, ...}}; rollback = inserting a copy of an older row under a
 *       fresh token; rows are never rewritten, only pruned beyond keep</li>
 *   <li>{@code player_sessions} — {@code {_id: long, playerUuid, serverId,
 *       token (unique), state, ...}}</li>
 *   <li>{@code player_stats} — {@code {playerUuid, statKey, statValue, updatedAt}},
 *       unique on (playerUuid, statKey); deltas via {@code $inc}</li>
 *   <li>{@code outbox} — {@code {_id: long, msgType, playerUuid, payloadJson,
 *       createdAt, publishedAt, attempts, lastError, claimedAt}}</li>
 *   <li>{@code snapshot_counters} — the fencing/history/session/outbox id sources</li>
 * </ul>
 *
 * <p>Timestamps are stored as BSON dates (millisecond precision); SQL uses
 * DATETIME(6), so sub-millisecond ordering is not preserved. Ids are int64.</p>
 */
public final class MongoPlayerStorage implements PlayerStorage {

    private static final Logger log = LoggerFactory.getLogger(MongoPlayerStorage.class);

    private static final int DUPLICATE_KEY = 11000;
    private static final Duration CLAIM_STALE_AFTER = Duration.ofSeconds(30);
    private static final int MAX_ERROR_LENGTH = 512;

    private static final TransactionOptions TX = TransactionOptions.builder()
            .readConcern(ReadConcern.MAJORITY)
            .writeConcern(WriteConcern.MAJORITY)
            .build();

    private static final FindOneAndUpdateOptions AFTER = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER);
    private static final FindOneAndUpdateOptions UPSERT_AFTER = new FindOneAndUpdateOptions()
            .upsert(true)
            .returnDocument(ReturnDocument.AFTER);

    private final MongoClient client;
    private final SnapshotSerializer serializer;
    private final StorageConfig config;
    private final MongoStatisticsDeltaApplier statistics = new MongoStatisticsDeltaApplier(this);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final MongoCollection<Document> players;
    private final MongoCollection<Document> snapshots;
    private final MongoCollection<Document> history;
    private final MongoCollection<Document> sessions;
    private final MongoCollection<Document> stats;
    private final MongoCollection<Document> outbox;
    private final MongoCollection<Document> counters;

    private MongoPlayerStorage(MongoClient client, MongoDatabase database, SnapshotSerializer serializer, StorageConfig config) {
        this.client = client;
        this.serializer = serializer;
        this.config = config;
        this.players = database.getCollection(MongoCollections.PLAYERS);
        this.snapshots = database.getCollection(MongoCollections.PLAYER_SNAPSHOTS);
        this.history = database.getCollection(MongoCollections.PLAYER_SNAPSHOT_HISTORY);
        this.sessions = database.getCollection(MongoCollections.PLAYER_SESSIONS);
        this.stats = database.getCollection(MongoCollections.PLAYER_STATS);
        this.outbox = database.getCollection(MongoCollections.OUTBOX);
        this.counters = database.getCollection(MongoCollections.SNAPSHOT_COUNTERS);
    }

    /**
     * Builds a storage instance from the common Mongo settings: client, then
     * bootstrap (collections/indexes/counters — the Mongo equivalent of
     * migrations), fail fast on any boot problem.
     *
     * @param settings      the {@code database.mongodb} block
     * @param serialization how snapshots are (de)serialized on disk
     * @param storageConfig history keep, session lease, leak detection (dev, JDBC-only)
     * @throws lol.candyriya.continuum.storage.api.SchemaMigrationException when the bootstrap cannot prepare the database
     */
    public static MongoPlayerStorage open(MongoSettings settings,
                                          SerializationConfig serialization,
                                          StorageConfig storageConfig) {
        return open(MongoStorageConfig.from(settings), serialization, storageConfig);
    }

    /**
     * Builds a storage instance from a fully-specified client configuration.
     *
     * @param config        connection and pool tuning
     * @param serialization how snapshots are (de)serialized on disk
     * @param storageConfig history keep, session lease, leak detection (dev, JDBC-only)
     * @throws lol.candyriya.continuum.storage.api.SchemaMigrationException when the bootstrap cannot prepare the database
     */
    public static MongoPlayerStorage open(MongoStorageConfig config,
                                          SerializationConfig serialization,
                                          StorageConfig storageConfig) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(serialization, "serialization must not be null");
        Objects.requireNonNull(storageConfig, "storageConfig must not be null");
        if (storageConfig.leakDetection()) {
            log.info("leakDetection is a JDBC pool knob; the MongoDB driver pool has no equivalent, ignoring");
        }
        MongoClient client = null;
        try {
            client = MongoClients.create(config.toClientSettings());
            MongoDatabase database = client.getDatabase(config.database());
            MongoSchemaMigrations.ensure(database);
            log.info("MongoDB storage ready (database={}, minPool={}, maxPool={}, keepLast={})",
                    config.database(), config.minPoolSize(), config.maxPoolSize(), storageConfig.historyKeepLast());
            return new MongoPlayerStorage(client, database, new SnapshotSerializer(serialization), storageConfig);
        } catch (RuntimeException e) {
            if (client != null) {
                try {
                    client.close();
                } catch (RuntimeException closeFailure) {
                    e.addSuppressed(closeFailure);
                    log.warn("failed to close the MongoDB client after a boot failure: {}", closeFailure.getMessage());
                }
            }
            throw e;
        }
    }

    // --- internals used by the stats applier --------------------------------------

    MongoCollection<Document> statsCollection() {
        ensureOpen();
        return stats;
    }

    void ensureOpen() {
        if (closed.get()) {
            throw StorageException.unavailable("storage is closed", null);
        }
    }

    /**
     * Runs {@code work} inside a transaction; retries are the driver's business
     * (transient errors), everything that surfaces is translated to
     * {@link StorageException} except {@link DuplicateKeySignal}, which callers
     * with a first-write race can absorb.
     */
    <T> T inTransaction(Function<ClientSession, T> work) {
        ensureOpen();
        try (ClientSession session = client.startSession()) {
            return session.withTransaction(() -> work.apply(session), TX);
        } catch (DuplicateKeySignal e) {
            throw e;
        } catch (MongoException e) {
            if (e.getCode() == DUPLICATE_KEY) {
                // non-transient duplicate key: signals a lost first-write race, callers decide how to absorb it
                throw new DuplicateKeySignal(e);
            }
            throw StorageException.unavailable("mongo transaction failed: " + e.getMessage(), e);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            // a callback invariant broke (vanished doc, malformed stored state) — not a transport failure, still fatal to the call
            throw StorageException.unavailable("mongo transaction failed: " + e.getMessage(), e);
        }
    }

    private <T> T attempt(String operation, Supplier<T> work) {
        ensureOpen();
        try {
            return work.get();
        } catch (StorageException e) {
            throw e;
        } catch (MongoException e) {
            throw StorageException.unavailable(operation + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // stored data failed to decode (bad uuid, missing field, unknown state) — same failure class as SQL's row-mapping errors
            throw StorageException.unavailable(operation, e);
        }
    }

    // --- snapshots -----------------------------------------------------------------

    @Override
    public Optional<PlayerSnapshot> load(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        return attempt("failed to load snapshot for " + playerId, () -> {
            Document doc = snapshots.find(eq("_id", playerId.toString())).first();
            if (doc == null) {
                return Optional.<PlayerSnapshot>empty();
            }
            SnapshotEnvelope envelope = readEnvelope(payloadOf(doc), playerId);
            return Optional.of(new PlayerSnapshot(envelope, requireLong(doc, "fencingToken"), requireInstant(doc, "savedAt")));
        });
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
        Date now = new Date();

        // a poisoned transaction can surface as a non-transient duplicate key on the first-write path;
        // one bounded retry re-examines the committed state and converges deterministically
        for (int attempt = 0; ; attempt++) {
            try {
                return doSave(snapshot, fencingToken, opts, payload, uuid, now);
            } catch (DuplicateKeySignal e) {
                if (attempt >= 1) {
                    throw StorageException.unavailable("first-write race for " + snapshot.playerId() + " did not settle after a retry", e.getCause());
                }
                log.debug("lost the first-write race for {}, retrying the save once", uuid);
            }
        }
    }

    private SaveResult doSave(PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                              byte[] payload, String uuid, Date now) {
        return inTransaction(session -> {
            Document fields = snapshotFields(snapshot, fencingToken, opts, payload, now);
            Bson guarded = and(eq("_id", uuid), lt("fencingToken", fencingToken));

            // Path A (happy path): one guarded write, no prior read. Null means no match.
            Document updated = snapshots.findOneAndUpdate(session, guarded, new Document("$set", fields), AFTER);
            if (updated != null) {
                return won(session, snapshot, fencingToken, opts, payload, uuid, now);
            }
            // Path A matched nothing: either a newer session owns the row, or it is absent.
            Document current = snapshots.find(session, eq("_id", uuid))
                    .projection(Projections.include("fencingToken"))
                    .first();
            if (current != null && requireLong(current, "fencingToken") >= fencingToken) {
                // a newer (or equal) session owns the row — abstain completely
                return SaveResult.staleToken(requireLong(current, "fencingToken"));
            }
            // First write (or a concurrent winner the driver must see first). The guarded filter keeps
            // this safe even if the row appears between the two statements: worst case it is a no-op
            // or a duplicate key the caller absorbs.
            snapshots.findOneAndUpdate(session, guarded, new Document("$set", fields), UPSERT_AFTER);
            return won(session, snapshot, fencingToken, opts, payload, uuid, now);
        });
    }

    private SaveResult won(ClientSession session, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                           byte[] payload, String uuid, Date now) {
        long historyId = nextId(session, MongoCollections.COUNTER_HISTORY);
        history.insertOne(session, historyDocument(historyId, snapshot, fencingToken, opts, payload, uuid, now));
        pruneHistory(session, uuid);
        players.updateOne(session, eq("_id", uuid),
                Updates.combine(Updates.set("updatedAt", now), Updates.setOnInsert("createdAt", now)),
                new UpdateOptions().upsert(true));
        for (SaveOptions.OutboxDraft draft : opts.outbox()) {
            insertOutboxInTransaction(session, draft.msgType(), snapshot.playerId(), draft.payloadJson(), now);
        }
        return SaveResult.saved(historyId);
    }

    private void pruneHistory(ClientSession session, String uuid) {
        List<Long> keep = history.find(session, eq("playerUuid", uuid))
                .projection(Projections.include("_id"))
                .sort(Sorts.orderBy(List.of(Sorts.descending("fencingToken"), Sorts.descending("_id"))))
                .limit(config.historyKeepLast())
                .into(new ArrayList<>())
                .stream()
                .map(MongoPlayerStorage::requireLongId)
                .toList();
        if (keep.size() == config.historyKeepLast()) {
            // everything older than the kept window goes; $nin with exactly keep.size() ids is precisely the tail
            history.deleteMany(session, and(eq("playerUuid", uuid), nin("_id", keep)));
        }
    }

    private Document snapshotFields(PlayerSnapshot snapshot, long fencingToken, SaveOptions opts, byte[] payload, Date now) {
        return new Document()
                .append("schemaVersion", snapshot.schemaVersion())
                .append("payload", new Binary(BsonBinarySubType.BINARY, payload))
                .append("fencingToken", fencingToken)
                .append("savedAt", now)
                .append("sourceBackend", opts.sourceBackend())
                .append("reason", opts.reason())
                .append("modules", new ArrayList<>(snapshot.moduleIds()));
    }

    private Document historyDocument(long historyId, PlayerSnapshot snapshot, long fencingToken, SaveOptions opts,
                                     byte[] payload, String uuid, Date now) {
        return new Document()
                .append("_id", historyId)
                .append("playerUuid", uuid)
                .append("schemaVersion", snapshot.schemaVersion())
                .append("payload", new Binary(BsonBinarySubType.BINARY, payload))
                .append("fencingToken", fencingToken)
                .append("savedAt", now)
                .append("sourceBackend", opts.sourceBackend())
                .append("reason", opts.reason())
                .append("modules", new ArrayList<>(snapshot.moduleIds()));
    }

    private void insertOutboxInTransaction(ClientSession session, String msgType, UUID playerId, String payloadJson, Date now) {
        long id = nextId(session, MongoCollections.COUNTER_OUTBOX);
        outbox.insertOne(session, outboxDocument(id, msgType, playerId, payloadJson, now));
    }

    private static Document outboxDocument(long id, String msgType, UUID playerId, String payloadJson, Date now) {
        return new Document()
                .append("_id", id)
                .append("msgType", msgType)
                .append("playerUuid", playerId.toString())
                .append("payloadJson", payloadJson)
                .append("createdAt", now)
                .append("publishedAt", null)
                .append("attempts", 0)
                .append("lastError", null)
                .append("claimedAt", null);
    }

    private long nextId(ClientSession session, String key) {
        Document doc = counters.findOneAndUpdate(session, eq("_id", key),
                Updates.inc("value", 1L), UPSERT_AFTER);
        if (doc == null) {
            throw new IllegalStateException("counter '" + key + "' vanished during an increment");
        }
        return requireLong(doc, "value");
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
        Date dbNow = Date.from(now);
        Date leaseUntil = Date.from(now.plus(config.sessionLease()));

        try {
            return inTransaction(session -> {
                // issuance inside the tx: an aborted acquire rolls the increment back, so aborted
                // acquires burn no tokens
                long token = nextId(session, MongoCollections.COUNTER_FENCE);
                players.updateOne(session, eq("_id", uuid),
                        Updates.combine(Updates.set("updatedAt", dbNow), Updates.setOnInsert("createdAt", dbNow)),
                        new UpdateOptions().upsert(true));
                // ownership claim: anything still marked open for this player yields
                sessions.updateMany(session,
                        and(eq("playerUuid", uuid), lt("token", token), ne("state", SessionState.RELEASED.name())),
                        Updates.combine(
                                Updates.set("state", SessionState.RELEASED.name()),
                                Updates.set("endedAt", dbNow),
                                Updates.set("closeReason", "superseded")));
                long sessionId = nextId(session, MongoCollections.COUNTER_SESSION);
                sessions.insertOne(session, new Document()
                        .append("_id", sessionId)
                        .append("playerUuid", uuid)
                        .append("serverId", serverId)
                        .append("token", token)
                        .append("state", SessionState.CONNECTING.name())
                        .append("startedAt", dbNow)
                        .append("lastHeartbeat", dbNow)
                        .append("leaseExpiresAt", leaseUntil)
                        .append("endedAt", null)
                        .append("closeReason", null));
                // read back what the database actually stored — no hand-built records from locals
                Document stored = sessions.find(session, eq("_id", sessionId)).first();
                if (stored == null) {
                    throw new IllegalStateException("session with id " + sessionId + " vanished right after insert");
                }
                return toSessionRecord(stored);
            });
        } catch (DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to acquire session for " + playerId, e.getCause());
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
        String uuid = playerId.toString();
        return attempt("failed to transition session for " + playerId, () -> {
            Document updated = sessions.findOneAndUpdate(
                    and(eq("playerUuid", uuid), eq("token", fencingToken), ne("state", SessionState.RELEASED.name())),
                    Updates.set("state", state.name()), AFTER);
            return Optional.ofNullable(updated).map(MongoPlayerStorage::toSessionRecord);
        });
    }

    @Override
    public Optional<SessionRecord> heartbeatSession(UUID playerId, long fencingToken) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        String uuid = playerId.toString();
        Instant now = Instant.now();
        Date dbNow = Date.from(now);
        Date leaseUntil = Date.from(now.plus(config.sessionLease()));
        return attempt("failed to heartbeat session for " + playerId, () -> {
            Document updated = sessions.findOneAndUpdate(
                    and(eq("playerUuid", uuid), eq("token", fencingToken), ne("state", SessionState.RELEASED.name())),
                    Updates.combine(Updates.set("lastHeartbeat", dbNow), Updates.set("leaseExpiresAt", leaseUntil)),
                    AFTER);
            return Optional.ofNullable(updated).map(MongoPlayerStorage::toSessionRecord);
        });
    }

    @Override
    public boolean releaseSession(UUID playerId, long fencingToken) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        String uuid = playerId.toString();
        Date now = new Date();
        return attempt("failed to release session for " + playerId, () -> {
            Document updated = sessions.findOneAndUpdate(
                    and(eq("playerUuid", uuid), eq("token", fencingToken), ne("state", SessionState.RELEASED.name())),
                    Updates.combine(
                            Updates.set("state", SessionState.RELEASED.name()),
                            Updates.set("endedAt", now),
                            Updates.set("closeReason", "released")),
                    AFTER);
            return updated != null;
        });
    }

    @Override
    public Optional<SessionRecord> getSession(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        String uuid = playerId.toString();
        return attempt("failed to read session for " + playerId, () ->
                Optional.ofNullable(sessions.find(eq("playerUuid", uuid))
                        .sort(Sorts.descending("token"))
                        .first())
                        .map(MongoPlayerStorage::toSessionRecord));
    }

    // --- history -------------------------------------------------------------------

    @Override
    public List<SnapshotSummary> listHistory(UUID playerId, int limit) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        String uuid = playerId.toString();
        return attempt("failed to list history for " + playerId, () -> {
            List<Document> rows = history.find(eq("playerUuid", uuid))
                    .projection(Projections.exclude("payload"))
                    .sort(Sorts.orderBy(List.of(Sorts.descending("fencingToken"), Sorts.descending("_id"))))
                    .limit(limit)
                    .into(new ArrayList<>());
            List<SnapshotSummary> summaries = new ArrayList<>(rows.size());
            for (Document row : rows) {
                summaries.add(toSnapshotSummary(row));
            }
            return List.copyOf(summaries);
        });
    }

    @Override
    public Optional<PlayerSnapshot> loadSnapshot(long snapshotId) throws StorageException {
        if (snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1, got " + snapshotId);
        }
        return attempt("failed to load snapshot " + snapshotId, () -> {
            Document doc = history.find(eq("_id", snapshotId)).first();
            if (doc == null) {
                return Optional.<PlayerSnapshot>empty();
            }
            UUID playerId = UUID.fromString(doc.getString("playerUuid"));
            SnapshotEnvelope envelope = readEnvelope(payloadOf(doc), playerId);
            return Optional.of(new PlayerSnapshot(envelope, requireLong(doc, "fencingToken"), requireInstant(doc, "savedAt")));
        });
    }

    // --- statistics ----------------------------------------------------------------

    @Override
    public PlayerStats readStats(UUID playerId) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        String uuid = playerId.toString();
        return attempt("failed to read stats for " + playerId, () -> {
            List<Document> rows = stats.find(eq("playerUuid", uuid)).into(new ArrayList<>());
            Map<String, Long> values = new LinkedHashMap<>();
            for (Document row : rows) {
                values.put(row.getString("statKey"), requireLong(row, "statValue"));
            }
            return new PlayerStats(playerId, values);
        });
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
        Date now = new Date();
        try {
            inTransaction(session -> {
                this.stats.deleteMany(session, eq("playerUuid", uuid));
                if (!copy.isEmpty()) {
                    List<Document> docs = new ArrayList<>(copy.size());
                    for (Map.Entry<String, Long> entry : copy.entrySet()) {
                        docs.add(new Document()
                                .append("playerUuid", uuid)
                                .append("statKey", entry.getKey())
                                .append("statValue", entry.getValue())
                                .append("updatedAt", now));
                    }
                    this.stats.insertMany(session, docs);
                }
                return null;
            });
        } catch (DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to write stats baseline for " + playerId, e.getCause());
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
        Date now = new Date();
        try {
            return inTransaction(session -> {
                long id = nextId(session, MongoCollections.COUNTER_OUTBOX);
                outbox.insertOne(session, outboxDocument(id, msgType, playerId, payloadJson, now));
                return new OutboxEntry(id, msgType, playerId, payloadJson, now.toInstant(), null, 0, null);
            });
        } catch (DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to append outbox event " + msgType, e.getCause());
        }
    }

    @Override
    public List<OutboxEntry> pollOutbox(int limit) throws StorageException {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        return attempt("failed to poll outbox", () -> {
            List<Document> rows = outbox.find(eq("publishedAt", null))
                    .sort(Sorts.ascending("_id"))
                    .limit(limit)
                    .into(new ArrayList<>());
            List<OutboxEntry> entries = new ArrayList<>(rows.size());
            for (Document row : rows) {
                entries.add(toOutboxEntry(row));
            }
            return List.copyOf(entries);
        });
    }

    /**
     * Claims and publishes a batch of outbox entries.
     *
     * <p>Claim protocol (there is no {@code SKIP LOCKED} in MongoDB, so a
     * claimed-flag design is used instead, all transactional so double-claim is
     * impossible):</p>
     * <ol>
     *   <li>in one transaction, repeatedly {@code findOneAndUpdate} the oldest
     *       entry matching {@code publishedAt == null, attempts < maxAttempts}
     *       and {@code claimedAt == null OR claimedAt < now - 30s}, setting
     *       {@code claimedAt = now}; the transaction is the claim — a concurrent
     *       publisher's competing claim hits a write conflict and the driver's
     *       retry pass observes the claimed entry and skips it;</li>
     *   <li>entries claimed by a crashed publisher become re-claimable once
     *       their {@code claimedAt} is older than 30s, so a crashed run costs at
     *       most a 30s delay and consumers see a duplicate (id is the natural
     *       idempotency key);</li>
     *   <li>the broker calls happen OUTSIDE any transaction — side effects must
     *       never ride a retried callback — and the outcome (published, or
     *       attempts+1 + error) is recorded in a second transaction;</li>
     *   <li>if the outcome transaction fails after the broker confirmed, the
     *       entries are republished on a later run; consumers must stay
     *       idempotent.</li>
     * </ol>
     */
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

        // phase 1: transactional claim — either the whole batch is claimed or none is
        List<OutboxEntry> claimed;
        try {
            claimed = inTransaction(session -> {
                List<OutboxEntry> out = new ArrayList<>(limit);
                Date staleBefore = Date.from(Instant.now().minus(CLAIM_STALE_AFTER));
                Date now = new Date();
                for (int i = 0; i < limit; i++) {
                    Document doc = outbox.findOneAndUpdate(session,
                            and(eq("publishedAt", null),
                                    lt("attempts", maxAttempts),
                                    or(eq("claimedAt", null), lt("claimedAt", staleBefore))),
                            Updates.set("claimedAt", now),
                            new FindOneAndUpdateOptions().sort(Sorts.ascending("_id")).returnDocument(ReturnDocument.AFTER));
                    if (doc == null) {
                        break;
                    }
                    out.add(toOutboxEntry(doc));
                }
                return out;
            });
        } catch (DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to claim outbox batch", e.getCause());
        }

        // phase 2: broker calls, no transaction — see the method javadoc claim protocol on why
        List<Long> publishedIds = new ArrayList<>();
        List<FailedMark> failures = new ArrayList<>();
        for (OutboxEntry entry : claimed) {
            boolean ok;
            String error = null;
            try {
                ok = publisher.publish(entry);
            } catch (Exception e) {
                ok = false;
                error = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName();
            }
            if (ok) {
                publishedIds.add(entry.id());
            } else {
                failures.add(new FailedMark(entry.id(), error == null ? "publisher reported failure" : truncate(error)));
            }
        }

        // phase 3: record the outcome in one transaction (all or nothing per batch)
        if (!publishedIds.isEmpty() || !failures.isEmpty()) {
            try {
                inTransaction(session -> {
                    Date stamp = new Date();
                    if (!publishedIds.isEmpty()) {
                        outbox.updateMany(session,
                                and(in("_id", publishedIds), eq("publishedAt", null)),
                                Updates.combine(Updates.set("publishedAt", stamp), Updates.set("claimedAt", null)));
                    }
                    for (FailedMark failed : failures) {
                        outbox.updateOne(session,
                                and(eq("_id", failed.id()), eq("publishedAt", null)),
                                Updates.combine(
                                        Updates.inc("attempts", 1),
                                        Updates.set("lastError", failed.error()),
                                        Updates.set("claimedAt", null)));
                    }
                    return null;
                });
            } catch (DuplicateKeySignal e) {
                throw StorageException.unavailable("failed to record outbox outcome", e.getCause());
            }
        }
        return publishedIds.size();
    }

    @Override
    public void markOutboxPublished(Collection<Long> ids) throws StorageException {
        Objects.requireNonNull(ids, "ids must not be null");
        ensureOpen();
        List<Long> copy = List.copyOf(ids);
        if (copy.isEmpty()) {
            return;
        }
        Date now = new Date();
        // single-statement, idempotent; no explicit transaction needed (publishedAt IS NULL keeps repeats no-ops)
        attempt("failed to mark " + copy.size() + " outbox entries published", () -> {
            outbox.updateMany(
                    and(in("_id", copy), eq("publishedAt", null)),
                    Updates.combine(Updates.set("publishedAt", now), Updates.set("claimedAt", null)));
            return null;
        });
    }

    @Override
    public void markOutboxFailed(long id, String error) throws StorageException {
        if (id < 1) {
            throw new IllegalArgumentException("id must be >= 1, got " + id);
        }
        ensureOpen();
        String message = truncate(error == null || error.isBlank() ? "unknown publisher error" : error);
        attempt("failed to mark outbox entry " + id + " as failed", () -> {
            outbox.updateOne(
                    and(eq("_id", id), eq("publishedAt", null)),
                    Updates.combine(Updates.inc("attempts", 1), Updates.set("lastError", message)));
            return null;
        });
    }

    // --- helpers ---------------------------------------------------------------------

    private SnapshotEnvelope readEnvelope(Binary payload, UUID expectedPlayer) throws StorageException {
        if (payload == null || payload.getData() == null || payload.getData().length == 0) {
            throw StorageException.unavailable("stored snapshot payload is empty", null);
        }
        try {
            return serializer.read(payload.getData());
        } catch (SnapshotSerializationException e) {
            throw StorageException.unavailable("stored snapshot for " + expectedPlayer + " could not be decoded", e);
        }
    }

    private static Binary payloadOf(Document doc) {
        Object raw = doc.get("payload");
        return raw instanceof Binary binary ? binary : null;
    }

    private static long requireLongId(Document doc) {
        return requireLong(doc, "_id");
    }

    private static long requireLong(Document doc, String key) {
        Object raw = doc.get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException("stored document is missing numeric field '" + key + "'");
        }
        return number.longValue();
    }

    private static Instant requireInstant(Document doc, String key) {
        Date value = doc.getDate(key);
        if (value == null) {
            throw new IllegalStateException("stored document is missing date field '" + key + "'");
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(Document doc, String key) {
        Date value = doc.getDate(key);
        return value == null ? null : value.toInstant();
    }

    private static SessionRecord toSessionRecord(Document doc) {
        String rawState = doc.getString("state");
        SessionState state;
        try {
            state = SessionState.valueOf(rawState);
        } catch (IllegalArgumentException e) {
            throw StorageException.unavailable("stored session state '" + rawState + "' is not a known SessionState", e);
        }
        return new SessionRecord(
                UUID.fromString(doc.getString("playerUuid")),
                requireLongId(doc),
                doc.getString("serverId"),
                requireLong(doc, "token"),
                state,
                requireInstant(doc, "startedAt"),
                requireInstant(doc, "lastHeartbeat"),
                requireInstant(doc, "leaseExpiresAt"),
                nullableInstant(doc, "endedAt"));
    }

    private static SnapshotSummary toSnapshotSummary(Document doc) {
        List<String> modules = doc.getList("modules", String.class);
        return new SnapshotSummary(
                requireLongId(doc),
                UUID.fromString(doc.getString("playerUuid")),
                ((Number) doc.get("schemaVersion")).intValue(),
                requireLong(doc, "fencingToken"),
                requireInstant(doc, "savedAt"),
                doc.getString("reason"),
                doc.getString("sourceBackend"),
                modules == null ? List.of() : List.copyOf(modules));
    }

    private static OutboxEntry toOutboxEntry(Document doc) {
        Object attemptsRaw = doc.get("attempts");
        int attempts = attemptsRaw instanceof Number number ? number.intValue() : 0;
        Instant published = nullableInstant(doc, "publishedAt");
        return new OutboxEntry(
                requireLongId(doc),
                doc.getString("msgType"),
                UUID.fromString(doc.getString("playerUuid")),
                doc.getString("payloadJson"),
                requireInstant(doc, "createdAt"),
                published,
                attempts,
                doc.getString("lastError"));
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("closing MongoDB storage client");
            client.close();
        }
    }

    /** Internal control-flow exception: a transaction lost a non-transient duplicate-key race. */
    static final class DuplicateKeySignal extends RuntimeException {
        DuplicateKeySignal(Throwable cause) {
            super(cause);
        }
    }

    private record FailedMark(long id, String error) {
    }
}
