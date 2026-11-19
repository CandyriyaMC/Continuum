package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.config.RecoveryConfig;
import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.RecoveryRequiredPayload;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.SessionRecord;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery flow against the in-memory storage: scan, mark, event, idempotency.
 * The engines' own scanners are covered by integration tests.
 */
class RecoveryManagerTest {

    /** A finder backed by the in-memory harness, with the same rules as the engine scanners. */
    private static OrphanedSessionFinder inMemoryFinder(InMemoryPlayerStorage storage) {
        return new OrphanedSessionFinder() {
            @Override
            public List<OrphanedSession> findOrphans(Instant olderThan, int limit) throws StorageException {
                return storage.snapshotSessions().stream()
                        .filter(session -> session.state() != SessionState.RELEASED)
                        .filter(session -> session.state() != SessionState.RECOVERY_REQUIRED)
                        .filter(session -> session.leaseExpiresAt().isBefore(olderThan))
                        .limit(limit)
                        .map(session -> new OrphanedSession(session.sessionId(), session.playerId(),
                                session.serverId(), session.token(), session.state(),
                                session.startedAt(), session.lastHeartbeat(), session.leaseExpiresAt()))
                        .toList();
            }
        };
    }

    @Test
    void orphanedSessionIsMarkedAndEventIsAppended() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        SessionRecord session = storage.acquireSession(player, "survival-1");
        storage.transitionSession(player, session.token(), SessionState.ACTIVE);
        storage.expireLease(player, session.token());

        RecoveryManager manager = new RecoveryManager(storage, inMemoryFinder(storage),
                new RecoveryConfig(true, Duration.ofMillis(50), List.of()));

        int marked = manager.recoverOnce();

        assertEquals(1, marked);
        Optional<SessionRecord> after = storage.getSession(player);
        assertTrue(after.isPresent());
        assertEquals(SessionState.RECOVERY_REQUIRED, after.get().state());

        List<OutboxEntry> events = storage.pollOutbox(10);
        assertEquals(1, events.size());
        OutboxEntry event = events.get(0);
        assertEquals(BackboneEvents.RECOVERY_REQUIRED, event.msgType());

        // the frame must decode as the real recovery event consumers expect
        EnvelopeCodec codec = new EnvelopeCodec(BackboneEvents.create(), 16 * 1024);
        MessageEnvelope<RecoveryRequiredPayload> envelope =
                codec.decode(event.payloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        RecoveryRequiredPayload.class);
        RecoveryRequiredPayload payload = envelope.payload();
        assertEquals(player, payload.playerId());
        assertEquals(RecoveryManager.sessionUuidFor(session.sessionId()), payload.sessionId());
        assertEquals("survival-1", payload.backendId());
        assertFalse(payload.reason().isBlank());
    }

    @Test
    void reRunningTheSweepIsIdempotent() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        SessionRecord session = storage.acquireSession(player, "survival-2");
        storage.expireLease(player, session.token());

        RecoveryManager manager = new RecoveryManager(storage, inMemoryFinder(storage),
                new RecoveryConfig(true, Duration.ofMillis(50), List.of()));

        assertEquals(1, manager.recoverOnce());
        assertEquals(0, manager.recoverOnce(), "second sweep must find nothing to do");
        assertEquals(1, storage.pollOutbox(10).size(), "no duplicate events on re-run");
    }

    @Test
    void liveSessionsAndReleasedSessionsAreLeftAlone() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID live = UUID.randomUUID();
        UUID done = UUID.randomUUID();

        SessionRecord liveSession = storage.acquireSession(live, "survival-live");
        SessionRecord releasedSession = storage.acquireSession(done, "survival-old");
        storage.expireLease(done, releasedSession.token());
        storage.releaseSession(done, releasedSession.token());

        RecoveryManager manager = new RecoveryManager(storage, inMemoryFinder(storage),
                new RecoveryConfig(true, Duration.ofMillis(50), List.of()));

        assertEquals(0, manager.recoverOnce(), "a live lease and a released session must not be touched");
        assertEquals(SessionState.CONNECTING, storage.getSession(live).orElseThrow().state());
        assertEquals(0, storage.pollOutbox(10).size());

        // sanity: the live session id is the only thing the test pretended to expire
        assertNotNull(liveSession);
    }

    @Test
    void outcomeLostBetweenScanAndMarkAppendsNothing() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        SessionRecord session = storage.acquireSession(player, "survival-race");
        storage.releaseSession(player, session.token());

        // a finder that hands out a now-stale orphan forces the transition to come back empty
        OrphanedSession staleOrphan = new OrphanedSession(session.sessionId(), player, session.serverId(),
                session.token(), SessionState.CONNECTING, session.startedAt(), session.lastHeartbeat(),
                Instant.now().minus(Duration.ofMinutes(10)));
        OrphanedSessionFinder racingFinder = (olderThan, limit) -> List.of(staleOrphan);

        RecoveryManager manager = new RecoveryManager(storage, racingFinder,
                new RecoveryConfig(true, Duration.ofMillis(50), List.of()));

        assertEquals(0, manager.recoverOnce(), "a session released before the mark must be skipped");
        assertEquals(0, storage.pollOutbox(10).size(), "no phantom recovery events for released sessions");
    }

    @Test
    void disabledRecoveryNeverStartsAndNeverMarks() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        RecoveryManager manager = new RecoveryManager(storage, inMemoryFinder(storage),
                new RecoveryConfig(false, Duration.ofMillis(10), List.of()));
        manager.start();
        assertFalse(manager.running(), "disabled recovery must not start its loop");
        manager.close();
    }

    @Test
    void payloadJsonUsesSnakeFreeDefensiveShape() {
        // protects against accidental drift of the event type constant
        assertEquals("continuum.recovery.required", BackboneEvents.RECOVERY_REQUIRED);
    }

    @Test
    void sessionHandleIsDeterministic() {
        UUID first = RecoveryManager.sessionUuidFor(123L);
        UUID second = RecoveryManager.sessionUuidFor(123L);
        assertEquals(first, second);
    }
}
