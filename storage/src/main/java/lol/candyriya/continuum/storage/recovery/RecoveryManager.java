package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.config.RecoveryConfig;
import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.RecoveryRequiredPayload;
import lol.candyriya.continuum.storage.api.PlayerStorage;
import lol.candyriya.continuum.storage.api.SessionRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle class that finds sessions abandoned by a crashed backend and
 * finalizes them.
 *
 * <p>On every tick (configured by {@code sync.recovery.interval}, gated by
 * {@code sync.recovery.enabled}):</p>
 * <ol>
 *   <li>scan for open sessions whose lease lapsed ({@link OrphanedSessionFinder});</li>
 *   <li>mark each {@code RECOVERY_REQUIRED} through the storage API — the
 *       fencing guard makes the transition safe against a concurrent live
 *       owner, and the operation is naturally idempotent (an already
 *       "finalized" session simply no longer matches the scan);</li>
 *   <li>append a {@code continuum.recovery.required} event to the outbox —
 *       recovery never publishes live; the outbox drain (sweeper or relay)
 *       carries it once the messaging wiring exists. The payload is a full
 *       envelope frame, so the relay can re-bind it; consumers must dedupe
 *       on {@code sessionId} — one event per recovery, but a retry after a
 *       crash between mark and append yields two frames for the same
 *       session;</li>
 *   <li>log one summary line per tick.</li>
 * </ol>
 *
 * <p>Crash-safety: transitions are idempotent and the scan re-runs every
 * interval, so running twice is harmless. The only non-atomic seam is
 * mark-then-append: a crash in between leaves the session marked
 * {@code RECOVERY_REQUIRED} without its event. The session row is the
 * authoritative signal; the event is advisory, so the seam stays small and
 * is documented rather than papered over with a second transition path.</p>
 *
 * <p>{@code RecoveryRequiredPayload.sessionId} is a UUID; the storage row id
 * (SQL bigint / Mongo int64) is mapped to a name-based UUID of it. That
 * value is stable across scans and safe for consumers to dedupe on.</p>
 */
public final class RecoveryManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    /** Orphan scan window per tick; leftovers are handled next interval. */
    static final int BATCH_SIZE = 100;

    /** Envelope size budget; recovery frames are tiny, this is generous. */
    private static final int MAX_ENVELOPE_BYTES = 16 * 1024;

    private static final String REASON = "session lease expired before the backend could finalize it";

    private final PlayerStorage storage;
    private final OrphanedSessionFinder finder;
    private final RecoveryConfig config;
    private final EnvelopeCodec codec;

    private volatile Thread loop;

    public RecoveryManager(PlayerStorage storage, OrphanedSessionFinder finder, RecoveryConfig config) {
        if (storage == null) {
            throw new IllegalArgumentException("storage must not be null");
        }
        if (finder == null) {
            throw new IllegalArgumentException("finder must not be null");
        }
        this.storage = storage;
        this.finder = finder;
        this.config = config == null
                ? new RecoveryConfig(true, Duration.ofSeconds(30), List.of())
                : config;
        this.codec = new EnvelopeCodec(BackboneEvents.create(), MAX_ENVELOPE_BYTES);
    }

    /** Starts the periodic scan. A no-op when recovery is disabled; idempotent. */
    public synchronized void start() {
        if (!config.enabled()) {
            log.info("session recovery disabled (sync.recovery.enabled=false); not starting");
            return;
        }
        if (loop != null) {
            return;
        }
        Thread thread = Thread.ofVirtual().name("continuum-recovery-", 0).unstarted(this::scanLoop);
        loop = thread;
        thread.start();
        log.info("session recovery started (interval {}ms, batch {})",
                interval().toMillis(), BATCH_SIZE);
    }

    public boolean running() {
        Thread current = loop;
        return current != null && current.isAlive();
    }

    /**
     * Stops the scan loop, giving an in-flight scan a bounded chance to finish.
     * Idempotent.
     */
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
        log.info("session recovery stopped");
    }

    /**
     * One scan pass; exposed for tests and for an on-demand ops trigger.
     *
     * @return how many sessions were marked {@code RECOVERY_REQUIRED}
     */
    public int recoverOnce() {
        List<OrphanedSession> orphans;
        try {
            orphans = finder.findOrphans(Instant.now(), BATCH_SIZE);
        } catch (ContinuumException e) {
            log.error("recovery scan failed; will retry next tick", e);
            return 0;
        }
        if (orphans.isEmpty()) {
            log.debug("recovery scan: no orphaned sessions");
            return 0;
        }
        int marked = 0;
        int events = 0;
        for (OrphanedSession orphan : orphans) {
            Optional<SessionRecord> transitioned = markRecovery(orphan);
            if (transitioned.isEmpty()) {
                log.debug("session {} (player {}) no longer needs recovery - state changed concurrently",
                        orphan.sessionId(), orphan.playerId());
                continue;
            }
            marked++;
            if (appendRecoveryEvent(orphan)) {
                events++;
            }
        }
        log.info("recovery scan complete: {} orphaned session(s), {} marked RECOVERY_REQUIRED, {} event(s) appended",
                orphans.size(), marked, events);
        return marked;
    }

    private Optional<SessionRecord> markRecovery(OrphanedSession orphan) {
        try {
            return storage.transitionSession(orphan.playerId(), orphan.token(), SessionState.RECOVERY_REQUIRED);
        } catch (ContinuumException e) {
            // leave the row open; the next tick retries with the full scan
            log.error("failed to mark session {} (player {}) as RECOVERY_REQUIRED", orphan.sessionId(), orphan.playerId(), e);
            return Optional.empty();
        }
    }

    private boolean appendRecoveryEvent(OrphanedSession orphan) {
        UUID sessionHandle = sessionUuidFor(orphan.sessionId());
        try {
            RecoveryRequiredPayload payload = new RecoveryRequiredPayload(
                    orphan.playerId(), sessionHandle, orphan.serverId(), REASON);
            MessageEnvelope<RecoveryRequiredPayload> envelope =
                    MessageEnvelope.create(BackboneEvents.RECOVERY_REQUIRED, orphan.playerId().toString(), payload);
            String frame = new String(codec.encode(envelope), StandardCharsets.UTF_8);
            storage.appendOutbox(BackboneEvents.RECOVERY_REQUIRED, orphan.playerId(), frame);
            return true;
        } catch (RuntimeException e) {
            // the sessions row is the authoritative signal and it is already marked;
            // losing the advisory event is preferable to rolling the mark back
            log.error("session {} marked RECOVERY_REQUIRED but its outbox event could not be appended; "
                    + "the session row still carries the state", orphan.sessionId(), e);
            return false;
        }
    }

    private Duration interval() {
        Duration configured = config.interval();
        return configured == null ? Duration.ofSeconds(30) : configured;
    }

    private void scanLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                recoverOnce();
            } catch (RuntimeException e) {
                // never let the loop die silently; the next tick scans again
                log.error("recovery sweep blew up; will retry next tick", e);
            }
            try {
                Thread.sleep(interval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Stable name-based UUID for a storage session row id; consumers dedupe on it.
     * Public because wiring and tests both need the exact same derivation.
     */
    public static UUID sessionUuidFor(long sessionId) {
        return UUID.nameUUIDFromBytes(("continuum-session-" + sessionId).getBytes(StandardCharsets.UTF_8));
    }
}
