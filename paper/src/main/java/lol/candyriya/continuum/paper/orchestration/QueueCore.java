package lol.candyriya.continuum.paper.orchestration;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lane bookkeeping behind {@link PlayerFifoQueue}, with no platform
 * dependencies — safe to exercise in unit tests without an executor.
 *
 * <p>A lane is a deque plus a draining flag guarded by one monitor. The drain
 * loop polls until the deque comes up empty, then clears the flag, so a
 * submission that arrives at the exact moment the loop decides to exit cannot
 * be lost. All cross-thread handoffs (submit vs. poll vs. eviction) happen
 * under that same monitor.</p>
 *
 * <p>Capacity accounting: only <em>queued</em> (not yet polled) items count
 * towards the per-player bound; the item currently draining is already out of
 * the deque. Rejection is the caller's signal to fail the future loudly.</p>
 */
final class QueueCore {

    private final int capacityPerPlayer;
    private final Duration idleEvictionAfter;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Lane> lanes = new ConcurrentHashMap<>();
    private volatile boolean closed;

    QueueCore(int capacityPerPlayer, Duration idleEvictionAfter, Clock clock) {
        if (capacityPerPlayer < 1) {
            throw new IllegalArgumentException("capacityPerPlayer must be >= 1, got " + capacityPerPlayer);
        }
        this.capacityPerPlayer = capacityPerPlayer;
        this.idleEvictionAfter = Objects.requireNonNull(idleEvictionAfter, "idleEvictionAfter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------- submission ----------

    /** What happened to an enqueued operation. */
    sealed interface EnqueueResult {
    }

    /** Queued. When {@code scheduleDrain} is true the caller must run a drain loop for the lane. */
    record Accepted(Lane lane, Item<?> item, boolean scheduleDrain) implements EnqueueResult {
    }

    /** Refused: the lane already holds {@code capacityPerPlayer} queued operations. */
    record RefusedFull() implements EnqueueResult {
    }

    /** Refused: the queue is closed. */
    record RefusedClosed() implements EnqueueResult {
    }

    /**
     * Queues {@code operation} for {@code playerId}. On success the future is
     * completed exactly once, by whoever runs the item (or by {@link #flushPending(String)}
     * on close). On refusal the future is completed exceptionally by this method.
     */
    <T> EnqueueResult enqueue(UUID playerId, FifoOperation<T> operation, CompletableFuture<T> future) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(future, "future");
        if (closed) {
            future.completeExceptionally(new IllegalStateException("fifo queue is closed"));
            return new RefusedClosed();
        }
        Lane lane = lanes.computeIfAbsent(playerId, id -> new Lane());
        synchronized (lane) {
            if (closed) {
                future.completeExceptionally(new IllegalStateException("fifo queue is closed"));
                return new RefusedClosed();
            }
            if (lane.pending.size() >= capacityPerPlayer) {
                // backpressure, not silence: the caller logs and fails the future
                future.completeExceptionally(new FifoQueueFullException(playerId, capacityPerPlayer));
                return new RefusedFull();
            }
            Item<T> item = new Item<>(operation, future);
            lane.pending.addLast(item);
            lane.lastActiveMillis = clock.millis();
            if (!lane.draining) {
                lane.draining = true;
                return new Accepted(lane, item, true);
            }
            return new Accepted(lane, item, false);
        }
    }

    /**
     * Removes a previously accepted item without executing it (used when the
     * drain task could not be scheduled). Identity-based; a no-op when a drain
     * already claimed the item. {@code scheduleDrain} must be whatever the
     * enqueue result reported.
     */
    boolean cancelAccepted(Lane lane, Item<?> item, boolean scheduleDrain) {
        synchronized (lane) {
            boolean removed = lane.pending.remove(item);
            if (removed && scheduleDrain) {
                lane.draining = false;
            }
            return removed;
        }
    }

    /**
     * Removes one item from the lane for execution; returns null when the lane
     * is empty, closing the drain window atomically (a submission racing the
     * exit cannot slip through unexecuted).
     */
    Item<?> pollOrRelease(Lane lane) {
        synchronized (lane) {
            Item<?> item = lane.pending.pollFirst();
            if (item == null) {
                lane.draining = false;
                lane.lastActiveMillis = clock.millis();
                return null;
            }
            return item;
        }
    }

    // ---------- diagnostics ----------

    int pendingCount(UUID playerId) {
        Lane lane = lanes.get(Objects.requireNonNull(playerId, "playerId"));
        if (lane == null) {
            return 0;
        }
        synchronized (lane) {
            return lane.pending.size();
        }
    }

    boolean isTracked(UUID playerId) {
        return lanes.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    int trackedLanes() {
        return lanes.size();
    }

    Set<UUID> trackedPlayers() {
        return new HashSet<>(lanes.keySet());
    }

    // ---------- eviction ----------

    /** Evicts lanes that are empty and idle at least the configured amount; returns how many. */
    int evictIdleNow() {
        long now = clock.millis();
        long threshold = idleEvictionAfter.toMillis();
        int evicted = 0;
        for (var entry : lanes.entrySet()) {
            UUID id = entry.getKey();
            Lane lane = entry.getValue();
            synchronized (lane) {
                if (lane.pending.isEmpty() && !lane.draining && now - lane.lastActiveMillis >= threshold) {
                    if (lanes.remove(id, lane)) {
                        evicted++;
                    }
                }
            }
        }
        return evicted;
    }

    // ---------- shutdown ----------

    /** Stops accepting operations; callers stop scheduling drains for lanes returned after this. */
    void beginClose() {
        closed = true;
    }

    /**
     * Fails every still-queued operation exceptionally and drops the lane
     * table; returns how many futures were completed this way. Operations
     * already polled for execution are not affected — they complete normally.
     */
    int flushPending(String reason) {
        int flushed = 0;
        for (Lane lane : lanes.values()) {
            synchronized (lane) {
                lane.draining = false;
                Item<?> item;
                while ((item = lane.pending.pollFirst()) != null) {
                    flushed++;
                    item.fail(new IllegalStateException(reason));
                }
            }
        }
        lanes.clear();
        return flushed;
    }

    // ---------- internals ----------

    /** One lane: a FIFO of queued items plus the drain bookkeeping. Guarded by its own monitor. */
    static final class Lane {
        private final Deque<Item<?>> pending = new ArrayDeque<>();
        private boolean draining;
        private long lastActiveMillis;
    }

    /** One queued operation together with the future exactly one party must complete. */
    static final class Item<T> {
        private final FifoOperation<T> operation;
        private final CompletableFuture<T> future;

        private Item(FifoOperation<T> operation, CompletableFuture<T> future) {
            this.operation = operation;
            this.future = future;
        }

        void run() {
            try {
                T result = operation.run();
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        void fail(Throwable t) {
            future.completeExceptionally(t);
        }
    }
}
