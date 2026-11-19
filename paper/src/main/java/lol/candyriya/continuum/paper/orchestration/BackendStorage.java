package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.config.DatabaseConfig;
import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.paper.backend.StorageLifecycle;
import lol.candyriya.continuum.paper.backend.StorageStatus;
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
import lol.candyriya.continuum.storage.sql.SqlPlayerStorage;
import lol.candyriya.continuum.storage.mongodb.MongoPlayerStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The one storage instance shared by the join gate and the sync pipeline.
 *
 * <p>Wraps the concrete storage (SQL via HikariCP or MongoDB, chosen by
 * {@code database.type} in the shared config) with two cross-cutting concerns:
 * an up/down health flag the gate probes for its degraded-storage refusal, and
 * delegation of every {@link PlayerStorage} call so a failing database flips
 * the flag instead of hiding in an inner stack trace. The flag is never used
 * to skip a write that would otherwise work — it drives the gate's join
 * refusal and the autosave sweep's noise control.</p>
 *
 * <p>Health is cached, never computed inline in {@link #status()}: a background
 * probe re-checks on a fixed interval (the shared config's
 * {@code sync.recovery.interval}), and every storage call updates the flag on
 * the fly (success flips it up, {@link StorageException} flips it down).</p>
 */
public final class BackendStorage implements PlayerStorage, StorageLifecycle, StorageHealth {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendStorage.class);
    private static final UUID PROBE_PLAYER = new UUID(0L, 0L);

    private final PlayerStorage delegate;
    private final String backendId;
    private final Duration probeInterval;
    private final AtomicReference<StorageStatus> status = new AtomicReference<>(StorageStatus.UP);
    private final ScheduledExecutorService probeExecutor;
    private volatile boolean closed;

    private BackendStorage(PlayerStorage delegate, String backendId, Duration probeInterval) {
        this.delegate = delegate;
        this.backendId = backendId;
        this.probeInterval = probeInterval;
        this.probeExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("continuum-storage-probe-", 0).factory());
    }

    /**
     * Opens the concrete storage chosen by the config. Fails fast (throws) when
     * the database cannot be prepared at boot — a backend that cannot persist
     * state must not run.
     */
    public static BackendStorage start(ContinuumConfig config) {
        DatabaseConfig database = config.database();
        StorageConfig storageConfig = StorageConfig.defaults();
        PlayerStorage delegate = switch (database.type()) {
            case MYSQL, MARIADB -> SqlPlayerStorage.open(database.mysql(), database.type(),
                    config.serialization(), storageConfig);
            case MONGODB -> MongoPlayerStorage.open(database.mongodb(),
                    config.serialization(), storageConfig);
        };
        BackendStorage storage = new BackendStorage(delegate, config.network().serverId(),
                config.sync().recovery().interval());
        storage.probeExecutor.scheduleWithFixedDelay(storage::probeSafely,
                storage.probeInterval.toMillis(), storage.probeInterval.toMillis(), TimeUnit.MILLISECONDS);
        LOGGER.info("storage backend ready (type={}, node={}, historyKeepLast={}, probeInterval={})",
                database.type(), config.network().serverId(), storageConfig.historyKeepLast(), storage.probeInterval);
        return storage;
    }

    /** The node's configured id; used as the source-backend tag on saves. */
    public String backendId() {
        return backendId;
    }

    /** True while the last probe or operation succeeded. */
    public boolean isUp() {
        return status.get() == StorageStatus.UP;
    }

    @Override
    public StorageStatus status() {
        return status.get();
    }

    // ---------- health tracking ----------

    private <T> T tracked(Supplier<T> op) {
        try {
            T result = op.get();
            status.set(StorageStatus.UP);
            return result;
        } catch (StorageException e) {
            status.set(StorageStatus.DEGRADED);
            throw e;
        } catch (RuntimeException e) {
            status.set(StorageStatus.DEGRADED);
            throw e;
        }
    }

    private void probeSafely() {
        try {
            delegate.load(PROBE_PLAYER);
            status.set(StorageStatus.UP);
        } catch (Throwable t) {
            if (status.compareAndSet(StorageStatus.UP, StorageStatus.DEGRADED)) {
                LOGGER.warn("storage health probe failed; storage is DEGRADED until a call succeeds again", t);
            }
        }
    }

    // ---------- PlayerStorage delegation ----------

    @Override
    public Optional<PlayerSnapshot> load(UUID playerId) throws StorageException {
        return tracked(() -> delegate.load(playerId));
    }

    @Override
    public SaveResult save(PlayerSnapshot snapshot, long fencingToken, SaveOptions options) throws StorageException {
        return tracked(() -> delegate.save(snapshot, fencingToken, options));
    }

    @Override
    public SessionRecord acquireSession(UUID playerId, String serverId) throws StorageException {
        return tracked(() -> delegate.acquireSession(playerId, serverId));
    }

    @Override
    public Optional<SessionRecord> transitionSession(UUID playerId, long fencingToken, SessionState state) throws StorageException {
        return tracked(() -> delegate.transitionSession(playerId, fencingToken, state));
    }

    @Override
    public Optional<SessionRecord> heartbeatSession(UUID playerId, long fencingToken) throws StorageException {
        return tracked(() -> delegate.heartbeatSession(playerId, fencingToken));
    }

    @Override
    public boolean releaseSession(UUID playerId, long fencingToken) throws StorageException {
        return tracked(() -> delegate.releaseSession(playerId, fencingToken));
    }

    @Override
    public Optional<SessionRecord> getSession(UUID playerId) throws StorageException {
        return tracked(() -> delegate.getSession(playerId));
    }

    @Override
    public List<SnapshotSummary> listHistory(UUID playerId, int limit) throws StorageException {
        return tracked(() -> delegate.listHistory(playerId, limit));
    }

    @Override
    public Optional<PlayerSnapshot> loadSnapshot(long snapshotId) throws StorageException {
        return tracked(() -> delegate.loadSnapshot(snapshotId));
    }

    @Override
    public PlayerStats readStats(UUID playerId) throws StorageException {
        return tracked(() -> delegate.readStats(playerId));
    }

    @Override
    public void writeStatsBaseline(UUID playerId, Map<String, Long> stats) throws StorageException {
        tracked(() -> {
            delegate.writeStatsBaseline(playerId, stats);
            return null;
        });
    }

    @Override
    public StatisticsDeltaApplier statistics() {
        // the applier performs its own writes; stats failures are reported by the statistics module
        return delegate.statistics();
    }

    @Override
    public OutboxEntry appendOutbox(String msgType, UUID playerId, String payloadJson) throws StorageException {
        return tracked(() -> delegate.appendOutbox(msgType, playerId, payloadJson));
    }

    @Override
    public List<OutboxEntry> pollOutbox(int limit) throws StorageException {
        return tracked(() -> delegate.pollOutbox(limit));
    }

    @Override
    public int publishOutbox(int limit, int maxAttempts, OutboxPublisher publisher) throws StorageException {
        return tracked(() -> delegate.publishOutbox(limit, maxAttempts, publisher));
    }

    @Override
    public void markOutboxPublished(Collection<Long> ids) throws StorageException {
        tracked(() -> {
            delegate.markOutboxPublished(ids);
            return null;
        });
    }

    @Override
    public void markOutboxFailed(long id, String error) throws StorageException {
        tracked(() -> {
            delegate.markOutboxFailed(id, error);
            return null;
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        probeExecutor.shutdownNow();
        try {
            delegate.close();
        } catch (Exception e) {
            LOGGER.warn("closing the storage delegate failed; pools may leak until JVM exit", e);
        }
    }
}
