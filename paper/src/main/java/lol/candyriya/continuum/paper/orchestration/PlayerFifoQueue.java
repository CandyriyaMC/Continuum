package lol.candyriya.continuum.paper.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-player FIFO serialization of every DB-referencing operation.
 *
 * <p>One lane per UUID; operations inside a lane run strictly in submission
 * order (captures, saves, restores, session transitions). Different lanes run
 * in parallel on a bounded pool of virtual threads — the pool is deliberately
 * the only concurrency limiter, there is no unbounded executor anywhere in this
 * class. The lane bookkeeping lives in {@link QueueCore} (no platform
 * dependencies, unit-tested directly); this class is the platform touch: the
 * pool, the idle-lane sweeper and the close handshake.</p>
 *
 * <p>Backpressure: a lane holds at most {@code capacityPerPlayer} pending
 * operations. Submitting beyond that fails the returned future with
 * {@link FifoQueueFullException} — the operation is refused, logged loudly,
 * never silently queued into memory we do not have.</p>
 *
 * <p>Idle lanes (empty and untouched for {@code idleEvictionAfter}) are evicted
 * by a low-frequency sweeper so a long-lived server does not accumulate empty
 * lane objects for every player that ever joined.</p>
 *
 * <p>The completed actor always fires exactly once: with the operation's result,
 * or exceptionally — never left outstanding. On close, operations still queued
 * (not yet running) are failed exceptionally once the pool has drained for a
 * bounded time.</p>
 */
public final class PlayerFifoQueue implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerFifoQueue.class);
    private static final long SWEEP_PERIOD_MILLIS = 30_000;
    private static final String CLOSED_REASON = "fifo queue is closed";

    private final int capacityPerPlayer;
    private final QueueCore core;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService sweeper;
    private volatile boolean closed;

    /** Production wiring: 256 slots per player by default, 5 minute idle eviction. */
    public PlayerFifoQueue(OrchestrationSettings settings) {
        this(settings, Clock.systemUTC());
    }

    /** Test wiring with an injectable clock. */
    public PlayerFifoQueue(OrchestrationSettings settings, Clock clock) {
        Objects.requireNonNull(settings, "settings");
        this.capacityPerPlayer = settings.fifoCapacityPerPlayer();
        this.core = new QueueCore(capacityPerPlayer, settings.idleEvictionAfter(), clock);
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory virtualFactory = Thread.ofVirtual()
                .name("continuum-fifo-", 0)
                .factory();
        // fixed-size pool of virtual threads: the bound is deliberate, virtual
        // threads are cheap but the DB behind them is not
        this.pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(settings.fifoWorkers(), virtualFactory);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(virtualFactory);
        this.sweeper.scheduleWithFixedDelay(this::evictIdleQuietly, SWEEP_PERIOD_MILLIS, SWEEP_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Enqueues {@code operation} for {@code playerId}. The future completes with
     * the operation's result, or exceptionally on failure refusal, or full queue.
     * Never blocks the caller.
     */
    public <T> CompletableFuture<T> submit(UUID playerId, FifoOperation<T> operation) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException(CLOSED_REASON));
            return future;
        }
        QueueCore.EnqueueResult result = core.enqueue(playerId, operation, future);
        switch (result) {
            case QueueCore.Accepted accepted -> {
                if (accepted.scheduleDrain()) {
                    try {
                        pool.execute(() -> drain(accepted.lane()));
                    } catch (RuntimeException e) {
                        // pool rejected (shutting down): take the item back out and fail it
                        if (core.cancelAccepted(accepted.lane(), accepted.item(), true)) {
                            future.completeExceptionally(e);
                        }
                    }
                }
            }
            case QueueCore.RefusedFull ignored ->
                    LOGGER.error("refusing to enqueue operation for {}: fifo full (capacity {})",
                            playerId, capacityPerPlayer);
            case QueueCore.RefusedClosed ignored -> {
                // future already completed by the core
            }
        }
        return future;
    }

    /** Pending operations for a player; for diagnostics and tests. */
    public int pendingCount(UUID playerId) {
        return core.pendingCount(playerId);
    }

    /** Whether the player currently has a lane (queued or draining); for diagnostics. */
    public boolean isTracked(UUID playerId) {
        return core.isTracked(playerId);
    }

    /** Number of tracked lanes; for diagnostics and tests. */
    public int trackedLanes() {
        return core.trackedLanes();
    }

    /** Players with a lane right now; for diagnostics. */
    public Set<UUID> trackedPlayers() {
        return core.trackedPlayers();
    }

    /** Evicts lanes that are empty and have been idle at least the configured amount. */
    public void evictIdleNow() {
        int evicted = core.evictIdleNow();
        if (evicted > 0) {
            LOGGER.debug("evicted {} idle fifo lane(s)", evicted);
        }
    }

    private void evictIdleQuietly() {
        try {
            evictIdleNow();
        } catch (Throwable t) {
            LOGGER.warn("idle lane eviction blew up; will try again next sweep", t);
        }
    }

    private void drain(QueueCore.Lane lane) {
        for (;;) {
            QueueCore.Item<?> item = core.pollOrRelease(lane);
            if (item == null) {
                return;
            }
            try {
                item.run();
            } catch (Throwable t) {
                // an operation blew up outside its own future completion path — complete it and keep the lane alive
                item.fail(t);
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        core.beginClose();
        sweeper.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                LOGGER.warn("fifo pool did not drain within 10s of close; in-flight operations were interrupted");
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // nothing queued may be left unresolved: fail whatever is still waiting
        int flushed = core.flushPending(CLOSED_REASON);
        if (flushed > 0) {
            LOGGER.warn("fifo close failed {} queued operation(s) that never ran", flushed);
        }
    }
}
