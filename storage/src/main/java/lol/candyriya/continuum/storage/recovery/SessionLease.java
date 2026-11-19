package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.storage.api.PlayerStorage;
import lol.candyriya.continuum.storage.api.SessionRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend-side heartbeat for owned sessions: renews the lease so the
 * recovery sweep does not reap sessions whose owner is alive and well.
 *
 * <p>Without this loop, a long-idle player's session would look orphaned
 * after {@code StorageConfig.sessionLease()} (90s by default) and get marked
 * {@code RECOVERY_REQUIRED} mid-session. Register each session the backend
 * acquires; unregister on release. The loop heartbeats every interval with
 * the fencing token, so a stolen or released lease simply stops renewing
 * instead of resurrecting.</p>
 *
 * <p>A heartbeat that comes back empty (the session was released elsewhere,
 * or superseded by a newer acquire) drops the registration and logs a
 * warning — silently keeping a dead registration would heartbeat a row the
 * owner no longer holds.</p>
 */
public final class SessionLease implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionLease.class);

    private final PlayerStorage storage;
    private final Duration interval;
    private final ConcurrentHashMap<UUID, Long> sessions = new ConcurrentHashMap<>();

    private volatile Thread loop;

    /**
     * @param storage  storage owning the sessions
     * @param interval renewal interval; use {@code sync.recovery.interval}
     */
    public SessionLease(PlayerStorage storage, Duration interval) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
    }

    /** Registers a session to renew. Re-registering rotates the token. */
    public void register(UUID playerId, long fencingToken) {
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        sessions.put(playerId, fencingToken);
    }

    /** Drops the registration. Idempotent. */
    public void unregister(UUID playerId) {
        sessions.remove(playerId);
    }

    /** How many sessions are currently registered. */
    public int registered() {
        return sessions.size();
    }

    /** Starts the renewal loop. Idempotent; a no-op while already running. */
    public synchronized void start() {
        if (loop != null) {
            return;
        }
        Thread thread = Thread.ofVirtual().name("continuum-session-lease-", 0).unstarted(this::renewLoop);
        loop = thread;
        thread.start();
        log.info("session lease heartbeat started (interval {}ms)", interval.toMillis());
    }

    public boolean running() {
        Thread current = loop;
        return current != null && current.isAlive();
    }

    /**
     * Renews every registered session once; exposed for tests and for an
     * on-demand trigger.
     *
     * @return how many sessions were renewed successfully
     */
    public int renewOnce() {
        int renewed = 0;
        for (var entry : sessions.entrySet()) {
            Optional<SessionRecord> result;
            try {
                result = storage.heartbeatSession(entry.getKey(), entry.getValue());
            } catch (ContinuumException e) {
                // transient storage trouble: keep the registration, retry next tick
                log.warn("heartbeat for player {} failed; keeping the registration", entry.getKey(), e);
                continue;
            }
            if (result.isEmpty()) {
                log.warn("heartbeat for player {} came back empty - the session was released or superseded elsewhere; dropping the registration", entry.getKey());
                sessions.remove(entry.getKey(), entry.getValue());
                continue;
            }
            renewed++;
        }
        return renewed;
    }

    /** Stops the renewal loop. Idempotent. */
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
        log.info("session lease heartbeat stopped");
    }

    private void renewLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                renewOnce();
            } catch (RuntimeException e) {
                // never let the loop die silently; the next tick renews again
                log.error("session lease renewal blew up; will retry next tick", e);
            }
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
