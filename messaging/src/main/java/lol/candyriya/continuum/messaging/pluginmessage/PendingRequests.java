package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.util.Preconditions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Table of in-flight requests: correlation id → future of the reply.
 *
 * <p>Backends register a request when they send a reply-expecting frame
 * ({@code continuum.handoff.request}, {@code continuum.handoff.ready},
 * {@code continuum.ping}) and complete the matching entry when the reply
 * lands. The proxy does not use this class — it only answers.</p>
 *
 * <h2>Bounds and eviction</h2>
 * <ul>
 *   <li>The table is bounded. Default {@value #DEFAULT_MAX_PENDING} entries;
 *       new registrations beyond the cap are rejected with
 *       {@link PendingRequestLimitException} instead of growing without
 *       limit when the peer goes silent.</li>
 *   <li>Entries leave the table on reply, on explicit failure, on timeout,
 *       or on {@link #close()}. Completed futures never linger.</li>
 *   <li>Each entry carries its own timeout (default {@value #DEFAULT_TIMEOUT_SECONDS}s),
 *       swept by a daemon scheduler thread — no polling on hot paths.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>Thread-safe; all public methods may be called from any thread.</li>
 *   <li>Completion races (reply vs timeout vs close) are resolved by an
 *       atomic {@code remove(key)}: exactly one path wins, the loser sees the
 *       entry gone and reports {@code false}. A reply is never completed
 *       twice and a timed-out request never completes late.</li>
 *   <li>Futures complete on the calling thread (or the sweeper's thread for
 *       timeouts). Keep completion listeners cheap or hand off to an
 *       executor.</li>
 * </ul>
 *
 * <p>Implementation note: {@code remove(id)} by value-free key is deliberate;
 * the table never reuses an id for a different request, and eviction pairs
 * the removal with the completion so the map and the futures never disagree.</p>
 */
public final class PendingRequests implements AutoCloseable {

    /** Default bound on in-flight requests: 1024. */
    public static final int DEFAULT_MAX_PENDING = 1024;

    /** Default per-request timeout in seconds. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 10;

    /** Default per-request timeout: 10 seconds. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);

    private record Entry(CompletableFuture<ProxyToBackendMessage> future) {
    }

    private final int maxPending;
    private final Duration timeout;
    private final ScheduledExecutorService sweeper;
    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();
    private final Object registryLock = new Object();
    private volatile boolean closed;

    /** Table with defaults: {@value #DEFAULT_MAX_PENDING} max entries, {@value #DEFAULT_TIMEOUT_SECONDS}s timeout. */
    public PendingRequests() {
        this(DEFAULT_MAX_PENDING, DEFAULT_TIMEOUT);
    }

    public PendingRequests(int maxPending, Duration timeout) {
        Preconditions.checkArgument(maxPending >= 1, "maxPending must be >= 1, got " + maxPending);
        Preconditions.notNull(timeout, "timeout");
        Preconditions.checkArgument(!timeout.isNegative() && !timeout.isZero(), "timeout must be positive, got " + timeout);
        this.maxPending = maxPending;
        this.timeout = timeout;
        // daemon threads only — request timeouts must never keep a JVM alive
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "continuum-pending-requests");
            thread.setDaemon(true);
            return thread;
        };
        this.sweeper = Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** The configured bound on in-flight requests. */
    public int maxPending() {
        return maxPending;
    }

    /** The configured per-request timeout. */
    public Duration timeout() {
        return timeout;
    }

    /** Current number of in-flight requests. Snapshot, not a reservation. */
    public int size() {
        return pending.size();
    }

    /**
     * Registers a new request under a fresh random correlation id.
     *
     * @throws PendingRequestLimitException when the table is full
     * @throws IllegalStateException        when the table is closed
     */
    public PendingRequest register() {
        return registerEntry(UUID.randomUUID());
    }

    /**
     * Registers a new request under an explicit correlation id.
     *
     * @return the handle for the in-flight request
     * @throws DuplicateCorrelationIdException when {@code correlationId} is already in flight
     * @throws PendingRequestLimitException    when the table is full
     * @throws IllegalStateException           when the table is closed
     */
    public PendingRequest register(UUID correlationId) {
        Preconditions.notNull(correlationId, "correlationId");
        return registerEntry(correlationId);
    }

    private PendingRequest registerEntry(UUID correlationId) {
        synchronized (registryLock) {
            checkOpen();
            Entry entry = new Entry(new CompletableFuture<>());
            if (pending.size() >= maxPending && !pending.containsKey(correlationId)) {
                throw new PendingRequestLimitException(maxPending);
            }
            if (pending.putIfAbsent(correlationId, entry) != null) {
                throw new DuplicateCorrelationIdException(correlationId);
            }
            try {
                sweeper.schedule(() -> expire(correlationId, entry), timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // sweeper gave up (shutting down); do not leave a zombie entry behind
                pending.remove(correlationId, entry);
                throw new IllegalStateException("pending-request sweeper rejected the timeout task", e);
            }
            // safety net: if the future is ever completed outside complete()/fail(), still evict the entry
            entry.future().whenComplete((reply, error) -> pending.remove(correlationId, entry));
            return new PendingRequest(correlationId, entry.future());
        }
    }

    /**
     * Completes the request {@code correlationId} with its reply.
     *
     * @return {@code true} when this call completed the entry; {@code false} when
     *         the id was not (or no longer) in flight — e.g. it already timed out
     * @throws IllegalArgumentException when {@code response} is null
     */
    public boolean complete(UUID correlationId, ProxyToBackendMessage response) {
        Preconditions.notNull(correlationId, "correlationId");
        Preconditions.notNull(response, "response");
        Entry entry = pending.remove(correlationId);
        if (entry == null) {
            return false;
        }
        entry.future().complete(response);
        return true;
    }

    /**
     * Fails the request {@code correlationId} exceptionally.
     *
     * @return {@code true} when this call failed the entry; {@code false} when the id was not in flight
     * @throws IllegalArgumentException when {@code error} is null
     */
    public boolean fail(UUID correlationId, Throwable error) {
        Preconditions.notNull(correlationId, "correlationId");
        Preconditions.notNull(error, "error");
        Entry entry = pending.remove(correlationId);
        if (entry == null) {
            return false;
        }
        entry.future().completeExceptionally(error);
        return true;
    }

    private void expire(UUID correlationId, Entry entry) {
        // the sweeper is the only other consumer of remove(); reply and timeout cannot both win
        if (pending.remove(correlationId, entry)) {
            entry.future().completeExceptionally(new PendingRequestTimeoutException(correlationId, timeout));
        }
    }

    /**
     * Fails every in-flight request with {@link IllegalStateException} and stops
     * the sweeper. Pending requests registered before the close() call are all
     * completed exceptionally; registrations after the close fail fast.
     */
    @Override
    public void close() {
        List<Entry> drained = new ArrayList<>();
        synchronized (registryLock) {
            if (closed) {
                return;
            }
            closed = true;
            for (UUID id : new ArrayList<>(pending.keySet())) {
                Entry entry = pending.remove(id);
                if (entry != null) {
                    drained.add(entry);
                }
            }
        }
        IllegalStateException closedError = new IllegalStateException("pending request table is closed");
        for (Entry entry : drained) {
            entry.future().completeExceptionally(closedError);
        }
        sweeper.shutdownNow();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("pending request table is closed");
        }
    }
}
