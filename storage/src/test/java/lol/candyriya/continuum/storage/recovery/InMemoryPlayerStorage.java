package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;
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

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Honest in-memory storage for recovery-path tests: real state, real
 * guards (fencing rules, terminal RELEASED, attempt budgets), no mocks of
 * the behavior under test. Snapshot load/save paths are minimal but
 * consistent; statistics are accumulated for real.
 */
public final class InMemoryPlayerStorage implements PlayerStorage {

    private final AtomicLong tokenCounter = new AtomicLong();
    private final AtomicLong sessionIdCounter = new AtomicLong();
    private final AtomicLong outboxId = new AtomicLong();
    private final ConcurrentHashMap<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PlayerSnapshot> history = new ConcurrentHashMap<>();
    private final AtomicLong historyId = new AtomicLong();
    private final ConcurrentHashMap<UUID, List<SessionRecord>> sessionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Long>> stats = new ConcurrentHashMap<>();
    private final List<OutboxEntry> outbox = new ArrayList<>();
    private final Duration lease;

    public InMemoryPlayerStorage() {
        this(Duration.ofSeconds(90));
    }

    public InMemoryPlayerStorage(Duration lease) {
        this.lease = lease;
    }

    // --- snapshots (minimal but consistent) ------------------------------------

    @Override
    public Optional<PlayerSnapshot> load(UUID playerId) throws StorageException {
        return Optional.ofNullable(snapshots.get(playerId));
    }

    @Override
    public SaveResult save(PlayerSnapshot snapshot, long fencingToken, SaveOptions options) throws StorageException {
        synchronized (this) {
            PlayerSnapshot current = snapshots.get(snapshot.playerId());
            if (current != null && current.fencingToken() >= fencingToken) {
                return new SaveResult.StaleToken(current.fencingToken());
            }
            snapshots.put(snapshot.playerId(), snapshot);
            long id = historyId.incrementAndGet();
            history.put(id, snapshot);
            for (SaveOptions.OutboxDraft draft : options.outbox()) {
                appendOutbox(draft.msgType(), snapshot.playerId(), draft.payloadJson());
            }
            return new SaveResult.Saved(id);
        }
    }

    // --- sessions ------------------------------------------------------------------

    @Override
    public SessionRecord acquireSession(UUID playerId, String serverId) throws StorageException {
        synchronized (sessionsByPlayer) {
            long token = tokenCounter.incrementAndGet();
            long id = sessionIdCounter.incrementAndGet();
            Instant now = Instant.now();
            SessionRecord session = new SessionRecord(playerId, id, serverId, token, SessionState.CONNECTING,
                    now, now, now.plus(lease), null);
            List<SessionRecord> sessions = sessionsByPlayer.computeIfAbsent(playerId, k -> new ArrayList<>());
            for (int i = 0; i < sessions.size(); i++) {
                SessionRecord existing = sessions.get(i);
                if (existing.state() != SessionState.RELEASED) {
                    sessions.set(i, new SessionRecord(existing.playerId(), existing.sessionId(), existing.serverId(),
                            existing.token(), SessionState.RELEASED, existing.startedAt(), existing.lastHeartbeat(),
                            existing.leaseExpiresAt(), now));
                }
            }
            sessions.add(session);
            return session;
        }
    }

    @Override
    public Optional<SessionRecord> transitionSession(UUID playerId, long fencingToken, SessionState state) throws StorageException {
        if (state == SessionState.CONNECTING || state == SessionState.RELEASED) {
            throw new IllegalArgumentException("transition target must not be CONNECTING or RELEASED");
        }
        List<SessionRecord> sessions = sessionsByPlayer.get(playerId);
        if (sessions == null) {
            return Optional.empty();
        }
        synchronized (sessions) {
            for (int i = 0; i < sessions.size(); i++) {
                SessionRecord existing = sessions.get(i);
                if (existing.token() == fencingToken && existing.state() != SessionState.RELEASED) {
                    SessionRecord updated = new SessionRecord(existing.playerId(), existing.sessionId(),
                            existing.serverId(), existing.token(), state, existing.startedAt(),
                            existing.lastHeartbeat(), existing.leaseExpiresAt(), existing.endedAt());
                    sessions.set(i, updated);
                    return Optional.of(updated);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<SessionRecord> heartbeatSession(UUID playerId, long fencingToken) throws StorageException {
        List<SessionRecord> sessions = sessionsByPlayer.get(playerId);
        if (sessions == null) {
            return Optional.empty();
        }
        synchronized (sessions) {
            for (int i = 0; i < sessions.size(); i++) {
                SessionRecord existing = sessions.get(i);
                if (existing.token() == fencingToken && existing.state() != SessionState.RELEASED) {
                    Instant now = Instant.now();
                    SessionRecord updated = new SessionRecord(existing.playerId(), existing.sessionId(),
                            existing.serverId(), existing.token(), existing.state(), existing.startedAt(),
                            now, now.plus(lease), existing.endedAt());
                    sessions.set(i, updated);
                    return Optional.of(updated);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean releaseSession(UUID playerId, long fencingToken) throws StorageException {
        List<SessionRecord> sessions = sessionsByPlayer.get(playerId);
        if (sessions == null) {
            return false;
        }
        synchronized (sessions) {
            for (int i = 0; i < sessions.size(); i++) {
                SessionRecord existing = sessions.get(i);
                if (existing.token() == fencingToken && existing.state() != SessionState.RELEASED) {
                    Instant now = Instant.now();
                    sessions.set(i, new SessionRecord(existing.playerId(), existing.sessionId(),
                            existing.serverId(), existing.token(), SessionState.RELEASED,
                            existing.startedAt(), existing.lastHeartbeat(), existing.leaseExpiresAt(), now));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Optional<SessionRecord> getSession(UUID playerId) throws StorageException {
        List<SessionRecord> sessions = sessionsByPlayer.get(playerId);
        if (sessions == null) {
            return Optional.empty();
        }
        synchronized (sessions) {
            return sessions.stream()
                    .max(Comparator.comparingLong(SessionRecord::token));
        }
    }

    /** Test-only: force a session's lease into the past to simulate a dead owner. */
    void expireLease(UUID playerId, long fencingToken) {
        List<SessionRecord> sessions = sessionsByPlayer.get(playerId);
        if (sessions == null) {
            return;
        }
        synchronized (sessions) {
            for (int i = 0; i < sessions.size(); i++) {
                SessionRecord existing = sessions.get(i);
                if (existing.token() == fencingToken) {
                    Instant past = Instant.now().minus(Duration.ofMinutes(10));
                    sessions.set(i, new SessionRecord(existing.playerId(), existing.sessionId(),
                            existing.serverId(), existing.token(), existing.state(), existing.startedAt(),
                            existing.lastHeartbeat(), past, existing.endedAt()));
                }
            }
        }
    }

    /** Test-only: all session rows, newest token last; for the recovery finder in tests. */
    List<SessionRecord> snapshotSessions() {
        List<SessionRecord> all = new ArrayList<>();
        for (List<SessionRecord> sessions : sessionsByPlayer.values()) {
            synchronized (sessions) {
                all.addAll(sessions);
            }
        }
        return List.copyOf(all);
    }

    // --- history -------------------------------------------------------------------

    @Override
    public List<SnapshotSummary> listHistory(UUID playerId, int limit) throws StorageException {
        return List.of();
    }

    @Override
    public Optional<PlayerSnapshot> loadSnapshot(long snapshotId) throws StorageException {
        return Optional.ofNullable(history.get(snapshotId));
    }

    // --- statistics ------------------------------------------------------------------

    @Override
    public PlayerStats readStats(UUID playerId) throws StorageException {
        Map<String, Long> values = stats.get(playerId);
        return new PlayerStats(playerId, values == null ? Map.of() : Map.copyOf(values));
    }

    @Override
    public void writeStatsBaseline(UUID playerId, Map<String, Long> values) throws StorageException {
        stats.put(playerId, new ConcurrentHashMap<>(values));
    }

    @Override
    public StatisticsDeltaApplier statistics() {
        return new StatisticsDeltaApplier() {
            @Override
            public long applyDelta(UUID playerId, String statKey, long delta) throws StorageException {
                Map<String, Long> map = stats.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                return map.merge(statKey, delta, Long::sum);
            }

            @Override
            public Map<String, Long> applyDeltas(UUID playerId, Map<String, Long> deltas) throws StorageException {
                Map<String, Long> map = stats.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
                Map<String, Long> result = new java.util.TreeMap<>();
                for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                    result.put(entry.getKey(), map.merge(entry.getKey(), entry.getValue(), Long::sum));
                }
                return result;
            }
        };
    }

    // --- outbox -----------------------------------------------------------------------

    @Override
    public OutboxEntry appendOutbox(String msgType, UUID playerId, String payloadJson) throws StorageException {
        synchronized (outbox) {
            long id = outboxId.incrementAndGet();
            OutboxEntry entry = new OutboxEntry(id, msgType, playerId, payloadJson,
                    Instant.now(), null, 0, null);
            outbox.add(entry);
            return entry;
        }
    }

    @Override
    public List<OutboxEntry> pollOutbox(int limit) throws StorageException {
        synchronized (outbox) {
            return outbox.stream()
                    .filter(entry -> entry.publishedAt() == null)
                    .sorted(Comparator.comparingLong(OutboxEntry::id))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public int publishOutbox(int limit, int maxAttempts, OutboxPublisher publisher) throws StorageException {
        synchronized (outbox) {
            List<OutboxEntry> claimed = outbox.stream()
                    .filter(entry -> entry.publishedAt() == null && entry.attempts() < maxAttempts)
                    .sorted(Comparator.comparingLong(OutboxEntry::id))
                    .limit(limit)
                    .toList();
            int published = 0;
            for (int i = 0; i < outbox.size(); i++) {
                OutboxEntry entry = outbox.get(i);
                if (claimed.stream().noneMatch(candidate -> candidate.id() == entry.id())) {
                    continue;
                }
                boolean ok;
                String error = null;
                try {
                    ok = publisher.publish(entry);
                } catch (Exception e) {
                    ok = false;
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                if (ok) {
                    outbox.set(i, new OutboxEntry(entry.id(), entry.msgType(), entry.playerId(),
                            entry.payloadJson(), entry.createdAt(), Instant.now(),
                            entry.attempts(), entry.lastError()));
                    published++;
                } else {
                    String truncated = error == null || error.isBlank() ? "publisher reported failure" : error;
                    if (truncated.length() > 512) {
                        truncated = truncated.substring(0, 512);
                    }
                    outbox.set(i, new OutboxEntry(entry.id(), entry.msgType(), entry.playerId(),
                            entry.payloadJson(), entry.createdAt(), null,
                            entry.attempts() + 1, truncated));
                }
            }
            return published;
        }
    }

    @Override
    public void markOutboxPublished(java.util.Collection<Long> ids) throws StorageException {
        synchronized (outbox) {
            for (int i = 0; i < outbox.size(); i++) {
                OutboxEntry entry = outbox.get(i);
                if (ids.contains(entry.id()) && entry.publishedAt() == null) {
                    outbox.set(i, new OutboxEntry(entry.id(), entry.msgType(), entry.playerId(),
                            entry.payloadJson(), entry.createdAt(), Instant.now(),
                            entry.attempts(), entry.lastError()));
                }
            }
        }
    }

    @Override
    public void markOutboxFailed(long id, String error) throws StorageException {
        synchronized (outbox) {
            for (int i = 0; i < outbox.size(); i++) {
                OutboxEntry entry = outbox.get(i);
                if (entry.id() == id && entry.publishedAt() == null) {
                    String truncated = error == null || error.isBlank() ? "unknown publisher error" : error;
                    if (truncated.length() > 512) {
                        truncated = truncated.substring(0, 512);
                    }
                    outbox.set(i, new OutboxEntry(entry.id(), entry.msgType(), entry.playerId(),
                            entry.payloadJson(), entry.createdAt(), null,
                            entry.attempts() + 1, truncated));
                }
            }
        }
    }

    @Override
    public void close() {
        // nothing pooled; tests keep state
    }
}
