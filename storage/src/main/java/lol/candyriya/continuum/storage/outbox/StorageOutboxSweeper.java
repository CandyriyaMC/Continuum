package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.config.RecoveryConfig;
import lol.candyriya.continuum.common.recovery.OutboxSweeper;
import lol.candyriya.continuum.common.recovery.OutboxSweepStats;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.OutboxPublisher;
import lol.candyriya.continuum.storage.api.PlayerStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The periodic drain for the transactional outbox. One virtual thread, a
 * fixed delay between sweeps, no overlap: a slow batch just postpones the
 * next tick.
 *
 * <p>Each tick publishes through {@link PlayerStorage#publishOutbox} in a
 * small batch; the storage layer handles claiming and records the outcome
 * atomically. Failures roll back into the outbox and are retried on the next
 * tick — the loop itself never dies on a bad batch, it counts and logs
 * (with the failure count, so a broken broker is one loud line every
 * interval, not a stack trace every second).</p>
 *
 * <p>Rows that exhausted their attempt budget are permanently skipped by the
 * claim; this sweeper notices them via an unlocked peek and logs each id
 * once per process lifetime (bounded LRU), so a poison row is visible
 * without spamming the log every five seconds.</p>
 *
 * <p>Exactly one drain per outbox: wire this with a storage-side
 * {@link OutboxPublisher} (e.g. the broker-confirmed publisher from the
 * platform wiring) or a relay with a claim claimer from this package —
 * never both against the same database.</p>
 */
public final class StorageOutboxSweeper implements OutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(StorageOutboxSweeper.class);

    /** Bound on the set-aside log-once memory; eldest ids age out. */
    private static final int SET_ASIDE_LOG_CAP = 2048;

    private final PlayerStorage storage;
    private final OutboxPublisher publisher;
    private final Duration interval;
    private final int batchSize;
    private final int maxAttempts;
    private final boolean enabled;

    private final Set<Long> setAsideLogged = new LinkedHashSet<>();

    private final AtomicLong sweeps = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong setAside = new AtomicLong();
    private final AtomicLong claimFailures = new AtomicLong();

    private volatile Thread loop;
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public StorageOutboxSweeper(PlayerStorage storage, OutboxPublisher publisher,
                                Duration interval, int batchSize, int maxAttempts, boolean enabled) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000, got " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.enabled = enabled;
    }

    /** Standalone wiring: 5s interval, batch 100, 10 attempts, enabled. */
    public static StorageOutboxSweeper defaults(PlayerStorage storage, OutboxPublisher publisher) {
        return new StorageOutboxSweeper(storage, publisher, Duration.ofSeconds(5), 100, 10, true);
    }

    /**
     * Recovery-config wiring: honors {@code sync.recovery.enabled} and
     * {@code sync.recovery.interval}; batch 100, attempt budget 10.
     */
    public static StorageOutboxSweeper fromRecoveryConfig(PlayerStorage storage,
                                                          OutboxPublisher publisher,
                                                          RecoveryConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        Duration interval = config.interval() == null ? Duration.ofSeconds(30) : config.interval();
        return new StorageOutboxSweeper(storage, publisher, interval, 100, 10, config.enabled());
    }

    @Override
    public synchronized void start() {
        if (!enabled) {
            log.info("outbox sweeper disabled (sync.recovery.enabled=false); not starting");
            return;
        }
        if (loop != null) {
            return;
        }
        Thread thread = Thread.ofVirtual().name("continuum-outbox-sweeper-", 0).unstarted(this::drainLoop);
        loop = thread;
        thread.start();
        log.info("outbox sweeper started (interval {}ms, batch {}, maxAttempts {})",
                interval.toMillis(), batchSize, maxAttempts);
    }

    @Override
    public boolean running() {
        Thread current = loop;
        return current != null && current.isAlive();
    }

    @Override
    public OutboxSweepStats stats() {
        return new OutboxSweepStats(sweeps.get(), published.get(),
                setAside.get(), claimFailures.get());
    }

    @Override
    public synchronized void close() {
        Thread current = loop;
        loop = null;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("outbox sweeper stopped");
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            sweepOnce();
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One sweep; exposed so tests (and an on-demand ops trigger) can drive it directly. */
    public void sweepOnce() {
        sweeps.incrementAndGet();
        try {
            int sent = storage.publishOutbox(batchSize, maxAttempts, publisher);
            published.addAndGet(sent);
            consecutiveFailures.set(0);
            logSetAsideOnce();
        } catch (RuntimeException e) {
            long failures = consecutiveFailures.incrementAndGet();
            claimFailures.incrementAndGet();
            // count the event as not-published for stats; the rows themselves keep their state
            log.error("outbox sweep failed (consecutive failure #{}); will retry next tick", failures, e);
        }
    }

    /** Peeks at the oldest unpublished rows and logs each set-aside id exactly once. */
    private void logSetAsideOnce() {
        List<OutboxEntry> peek;
        try {
            peek = storage.pollOutbox(batchSize);
        } catch (RuntimeException e) {
            log.warn("outbox set-aside inspection failed; skipping this tick", e);
            return;
        }
        for (OutboxEntry entry : peek) {
            if (entry.attempts() < maxAttempts) {
                continue;
            }
            if (loggedSetAside(entry.id())) {
                log.warn("outbox event {} (type {}) reached {} attempts and is set aside; publish it manually or investigate the consumer",
                        entry.id(), entry.msgType(), entry.attempts());
            }
        }
    }

    /** Returns true when this id was not logged before; bounds the memory, eldest first out. */
    private boolean loggedSetAside(long id) {
        synchronized (setAsideLogged) {
            if (setAsideLogged.contains(id)) {
                return false;
            }
            setAsideLogged.add(id);
            setAside.incrementAndGet();
            while (setAsideLogged.size() > SET_ASIDE_LOG_CAP) {
                Iterator<Long> it = setAsideLogged.iterator();
                it.next();
                it.remove();
            }
            return true;
        }
    }
}
