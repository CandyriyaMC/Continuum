package lol.candyriya.continuum.velocity.session;

import lol.candyriya.continuum.velocity.Metrics;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-player view of which backend holds them right now, as observed by the proxy.
 *
 * <p>This is the proxy-side inventory of live placements: it is updated from
 * the proxy's own events ({@code ServerConnectedEvent}, {@code DisconnectEvent},
 * {@code KickedFromServerEvent} hooks), never from the wire directly, so it
 * stays consistent with what the network actually did. Two uses:</p>
 * <ul>
 *     <li>{@code /continuum status} reads {@link #connectionsByBackend()} and
 *         {@link #awaitingBackendCount()} for the operator view;</li>
 *     <li>the kick reconciliation path reads {@link #isReconnectDebounced} to
 *         collapse flip-flopping reconnects into one episode.</li>
 * </ul>
 *
 * <p>A player whose backend kicked them is marked awaiting a backend outcome
 * until they connect somewhere (or leave the proxy) - the data plane keeps
 * waiting for that decision rather than re-seeding state.</p>
 */
public final class NetworkSessionRegistry implements AutoCloseable {

    private record SessionEntry(String username, String backendName, Instant connectedAt, int stormCount) {
    }

    private record AwaitingEntry(String backendName, String reason, Instant awaitingSince) {
    }

    private final Logger logger;
    private final Metrics metrics;

    private final ConcurrentHashMap<UUID, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AwaitingEntry> awaiting = new ConcurrentHashMap<>();
    private final ReconnectTracker reconnects = new ReconnectTracker();

    private volatile long reconnectDebounceSeconds;
    private volatile boolean closed;

    public NetworkSessionRegistry(Logger logger, Metrics metrics, long reconnectDebounceSeconds) {
        if (reconnectDebounceSeconds < 1 || reconnectDebounceSeconds > 600) {
            throw new IllegalArgumentException("reconnect-debounce-seconds must be within 1-600, got " + reconnectDebounceSeconds);
        }
        this.logger = logger;
        this.metrics = metrics;
        this.reconnectDebounceSeconds = reconnectDebounceSeconds;
    }

    /** Updates the tracked placement. Called from {@code ServerConnectedEvent}. */
    public void playerConnected(Player player, RegisteredServer server) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(server, "server");
        if (closed) {
            logger.debug("registry is closed; ignoring connect of player {}", player.getUniqueId());
            return;
        }
        UUID id = player.getUniqueId();
        String backend = server.getServerInfo().getName();
        Instant now = Instant.now();
        int storm = reconnects.record(id, now, windowMillis());

        sessions.put(id, new SessionEntry(player.getUsername(), backend, now, storm));
        awaiting.remove(id);

        if (storm >= 4) {
            logger.warn("reconnect storm: player {} ({}) connected {} times inside {}s; treating reconnects as one episode",
                    id, player.getUsername(), storm, reconnectDebounceSeconds);
        } else if (storm >= 2) {
            logger.info("player {} reconnected {} times inside {}s", id, storm, reconnectDebounceSeconds);
        }
        metrics.increment("continuum.sessions.connected");
    }

    /** Drops every trace of the player. Called from {@code DisconnectEvent}. */
    public void playerDisconnected(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        SessionEntry removed = sessions.remove(playerId);
        awaiting.remove(playerId);
        reconnects.remove(playerId);
        if (removed != null) {
            metrics.increment("continuum.sessions.disconnected");
            logger.debug("registry: player {} left (was on '{}')", playerId, removed.backendName());
        }
    }

    /**
     * Marks the player as awaiting a backend outcome: no longer counts as
     * connected, but still tracked (e.g. from {@code KickedFromServerEvent}).
     * Idempotent.
     */
    public void markAwaitingBackend(UUID playerId, String backendName, String reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(backendName, "backendName");
        if (closed) {
            logger.debug("registry is closed; ignoring awaiting mark for player {}", playerId);
            return;
        }
        sessions.remove(playerId);
        awaiting.put(playerId, new AwaitingEntry(backendName, reason == null ? "" : reason, Instant.now()));
        metrics.increment("continuum.sessions.awaiting");
    }

    /** True when the player transitioned at least once inside the debounce window. */
    public boolean isReconnectDebounced(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return reconnects.countInWindow(playerId, Instant.now(), windowMillis()) > 0;
    }

    /** Current placement for the player, when tracked. */
    public Optional<SessionSnapshot> snapshot(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        SessionEntry entry = sessions.get(playerId);
        AwaitingEntry waiting = awaiting.get(playerId);
        if (entry == null && waiting == null) {
            return Optional.empty();
        }
        if (entry != null) {
            return Optional.of(new SessionSnapshot(playerId, entry.backendName(), entry.connectedAt(),
                    entry.stormCount(), waiting != null, waiting != null ? waiting.reason() : ""));
        }
        return Optional.of(new SessionSnapshot(playerId, waiting.backendName(), waiting.awaitingSince(), 0, true, waiting.reason()));
    }

    /** Connections per backend, name-sorted. Backends with zero connections are omitted. */
    public Map<String, Integer> connectionsByBackend() {
        Map<String, Integer> grouped = sessions.values().stream()
                .collect(Collectors.groupingBy(SessionEntry::backendName, Collectors.summingInt(e -> 1)));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** Same data as {@link #connectionsByBackend()} shaped for the status command. */
    public List<BackendLoad> backendLoads() {
        return connectionsByBackend().entrySet().stream()
                .map(e -> new BackendLoad(e.getKey(), e.getValue()))
                .toList();
    }

    /** Players tracked as connected right now. */
    public int onlineCount() {
        return sessions.size();
    }

    /** Connections awaiting a backend outcome right now. */
    public int awaitingBackendCount() {
        return awaiting.size();
    }

    public long reconnectDebounceSeconds() {
        return reconnectDebounceSeconds;
    }

    /** Hot-updates the debounce window (reload command). */
    public void setReconnectDebounceSeconds(long seconds) {
        if (seconds < 1 || seconds > 600) {
            throw new IllegalArgumentException("reconnect-debounce-seconds must be within 1-600, got " + seconds);
        }
        this.reconnectDebounceSeconds = seconds;
    }

    @Override
    public void close() {
        closed = true;
        sessions.clear();
        awaiting.clear();
        reconnects.clear();
    }

    private long windowMillis() {
        return Duration.ofSeconds(reconnectDebounceSeconds).toMillis();
    }
}
