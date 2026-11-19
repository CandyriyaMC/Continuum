package lol.candyriya.continuum.paper.orchestration;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Shared fakes for the orchestration pipeline tests. */
final class Fakes {

    private Fakes() {
    }

    /** A player whose EntityScheduler runs submitted tasks inline and counts them. */
    static Player fakePlayer(UUID id) {
        EntityScheduler scheduler = (EntityScheduler) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(),
                new Class<?>[] {EntityScheduler.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "execute":
                            // plugin, task, retiredAction, delay
                            ((Runnable) args[1]).run();
                            return true;
                        case "run", "runDelayed", "runAtFixedRate":
                            return null;
                        case "toString":
                            return "TestEntityScheduler";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        return (Player) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "TestPlayer";
                    case "getScheduler" -> scheduler;
                    case "toString" -> "TestPlayer[" + id + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    static Plugin fakePlugin() {
        return (Plugin) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TestPlugin";
                    case "toString" -> "TestPlugin";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants.length > 0 ? constants[0] : null;
        }
        if (Optional.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Map.class.isAssignableFrom(type)) {
            return Map.of();
        }
        return null;
    }

    /** In-memory storage: token fencing is honored exactly like the real one checks it. */
    static final class FakePlayerStorage implements lol.candyriya.continuum.storage.api.PlayerStorage {

        private final AtomicLong tokenSequence = new AtomicLong();
        private final AtomicLong sessionSequence = new AtomicLong();
        private final AtomicLong historySequence = new AtomicLong();
        private final List<lol.candyriya.continuum.storage.api.SaveOptions> saveOptionsSeen = new CopyOnWriteArrayList<>();
        private final List<String> savesAttempted = new CopyOnWriteArrayList<>();
        private final List<String> released = new CopyOnWriteArrayList<>();
        private volatile lol.candyriya.continuum.common.snapshot.SnapshotEnvelope stored;
        private volatile long storedToken;
        private volatile lol.candyriya.continuum.common.session.SessionState transitionTo;
        private volatile boolean failOnSave;
        private volatile boolean failOnLoad;

        @Override
        public Optional<lol.candyriya.continuum.storage.api.PlayerSnapshot> load(UUID playerId) {
            if (failOnLoad) {
                throw lol.candyriya.continuum.common.error.StorageException.unavailable("boom", null);
            }
            if (stored == null) {
                return Optional.empty();
            }
            return Optional.of(new lol.candyriya.continuum.storage.api.PlayerSnapshot(stored, storedToken, Instant.now()));
        }

        @Override
        public lol.candyriya.continuum.storage.api.SaveResult save(
                lol.candyriya.continuum.storage.api.PlayerSnapshot snapshot,
                long fencingToken,
                lol.candyriya.continuum.storage.api.SaveOptions options) {
            if (failOnSave) {
                throw lol.candyriya.continuum.common.error.StorageException.unavailable("disk on fire", null);
            }
            savesAttempted.add(snapshot.playerId() + "@" + fencingToken);
            if (fencingToken < storedToken) {
                return lol.candyriya.continuum.storage.api.SaveResult.staleToken(storedToken);
            }
            stored = snapshot.envelope();
            storedToken = fencingToken;
            saveOptionsSeen.add(options);
            return lol.candyriya.continuum.storage.api.SaveResult.saved(historySequence.incrementAndGet() + 100);
        }

        @Override
        public lol.candyriya.continuum.storage.api.SessionRecord acquireSession(UUID playerId, String serverId) {
            long token = tokenSequence.incrementAndGet();
            long sessionId = sessionSequence.incrementAndGet();
            return new lol.candyriya.continuum.storage.api.SessionRecord(
                    playerId, sessionId, serverId, token,
                    lol.candyriya.continuum.common.session.SessionState.CONNECTING,
                    Instant.now(), Instant.now(), Instant.now().plusSeconds(90), null);
        }

        @Override
        public Optional<lol.candyriya.continuum.storage.api.SessionRecord> transitionSession(
                UUID playerId, long fencingToken, lol.candyriya.continuum.common.session.SessionState state) {
            transitionTo = state;
            return Optional.empty();
        }

        @Override
        public Optional<lol.candyriya.continuum.storage.api.SessionRecord> heartbeatSession(UUID playerId, long fencingToken) {
            return Optional.empty();
        }

        @Override
        public boolean releaseSession(UUID playerId, long fencingToken) {
            released.add(playerId + "@" + fencingToken);
            return fencingToken >= storedToken || storedToken == 0 || fencingToken == tokenSequence.get();
        }

        @Override
        public Optional<lol.candyriya.continuum.storage.api.SessionRecord> getSession(UUID playerId) {
            return Optional.empty();
        }

        @Override
        public List<lol.candyriya.continuum.storage.api.SnapshotSummary> listHistory(UUID playerId, int limit) {
            return List.of();
        }

        @Override
        public Optional<lol.candyriya.continuum.storage.api.PlayerSnapshot> loadSnapshot(long snapshotId) {
            return Optional.empty();
        }

        @Override
        public lol.candyriya.continuum.storage.api.PlayerStats readStats(UUID playerId) {
            return new lol.candyriya.continuum.storage.api.PlayerStats(playerId, Map.of());
        }

        @Override
        public void writeStatsBaseline(UUID playerId, Map<String, Long> stats) {
        }

        @Override
        public lol.candyriya.continuum.storage.api.StatisticsDeltaApplier statistics() {
            throw new UnsupportedOperationException("stats not exercised in these tests");
        }

        @Override
        public lol.candyriya.continuum.storage.api.OutboxEntry appendOutbox(String msgType, UUID playerId, String payloadJson) {
            return new lol.candyriya.continuum.storage.api.OutboxEntry(1, msgType, playerId, payloadJson,
                    Instant.now(), null, 0, null);
        }

        @Override
        public List<lol.candyriya.continuum.storage.api.OutboxEntry> pollOutbox(int limit) {
            return List.of();
        }

        @Override
        public int publishOutbox(int limit, int maxAttempts, lol.candyriya.continuum.storage.api.OutboxPublisher publisher) {
            return 0;
        }

        @Override
        public void markOutboxPublished(java.util.Collection<Long> ids) {
        }

        @Override
        public void markOutboxFailed(long id, String error) {
        }

        @Override
        public void close() {
        }

        // ---------- test knobs ----------

        int saveCalls() {
            return savesAttempted.size();
        }

        List<lol.candyriya.continuum.storage.api.SaveOptions> saveOptionsSeen() {
            return Collections.unmodifiableList(saveOptionsSeen);
        }

        List<String> released() {
            return Collections.unmodifiableList(released);
        }

        lol.candyriya.continuum.common.snapshot.SnapshotEnvelope stored() {
            return stored;
        }

        void forceStoredToken(long token) {
            this.storedToken = token;
        }

        void failOnSave() {
            this.failOnSave = true;
        }

        void failOnLoad() {
            this.failOnLoad = true;
        }
    }
}
