package lol.candyriya.continuum.paper.gate;

import lol.candyriya.continuum.common.i18n.TypedArgument;
import lol.candyriya.continuum.paper.PaperMessages;
import lol.candyriya.continuum.paper.backend.StorageProbe;
import lol.candyriya.continuum.paper.backend.StorageStatus;
import lol.candyriya.continuum.paper.config.GateConfig;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

import com.destroystokyo.paper.profile.PlayerProfile;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds joining players in a frozen state until their data is restored, then
 * hands control back.
 *
 * <p>Flow per join: the async spawn-decision event picks the spawn point
 * ({@link SpawnPolicy}); the join event calls {@link #enqueueRestore(Player)}
 * which freezes the player (spectator, hidden from everyone, invulnerable) and
 * either dispatches the wired {@link RestoreHandler} or lifts immediately when
 * none is wired (synchronization layer not present — the gate stays open).
 * While a session is active every gameplay-affecting event is cancelled at
 * {@code LOWEST} priority by {@link JoinGateListeners}; one {@code AtomicBoolean}
 * per session decides, so the hot path is a map lookup and a boolean read.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Call {@link #enqueueRestore(Player)} on the player's entity thread
 *       (the join event fires there on both Paper and Folia). Other callers
 *       are hopped onto the entity thread automatically.</li>
 *   <li>{@link #markReady(UUID)} and {@link #markFailed(UUID, String)} are the
 *       only ways to end a restore; both complete the future returned by
 *       {@code enqueueRestore} exactly once, from any thread.</li>
 *   <li>If nothing calls markReady/markFailed within
 *       {@code gate.restore-timeout-seconds}, the restore counts as failed:
 *       the player is kicked and the failure is recorded.</li>
 *   <li>{@code gate.max-failures} consecutive failures refuse further logins
 *       for {@code gate.failure-cooldown-minutes} — enforced at pre-login.</li>
 *   <li>On shutdown the gate is closed, not kicked: held players are released
 *       with control restored.</li>
 * </ul>
 */
public final class RestoreGate implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestoreGate.class);

    private final Plugin plugin;
    private final GateConfig config;
    private final PaperMessages messages;
    private final SpawnPolicy spawnPolicy;
    private final StorageProbe storageProbe;
    private final ScheduledExecutorService timer;

    private final java.util.concurrent.ConcurrentHashMap<UUID, GateSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final FailureTracker failures = new FailureTracker();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Nullable
    private volatile RestoreHandler restoreHandler;
    @Nullable
    private volatile JoinGateListeners listeners;

    public RestoreGate(Plugin plugin,
                       GateConfig config,
                       PaperMessages messages,
                       SpawnPolicy spawnPolicy,
                       StorageProbe storageProbe,
                       ScheduledExecutorService timer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.spawnPolicy = Objects.requireNonNull(spawnPolicy, "spawnPolicy");
        this.storageProbe = Objects.requireNonNull(storageProbe, "storageProbe");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.timer.scheduleWithFixedDelay(this::reapQuietly, 60, 60, TimeUnit.SECONDS);
    }

    // ---------- wiring ----------

    /**
     * Installs the restore handler exactly once. Must be called before {@link #arm()}.
     * Passing {@code null} is a no-op install (the gate lifts joins immediately).
     */
    public void installRestoreHandler(@Nullable RestoreHandler handler) {
        if (listeners != null) {
            throw new IllegalStateException("gate is already armed");
        }
        if (this.restoreHandler != null) {
            throw new IllegalStateException("a restore handler is already installed");
        }
        this.restoreHandler = handler;
    }

    /** Registers the gate listeners on the plugin manager. Called once, at the end of composition. */
    public void arm() {
        if (listeners != null) {
            throw new IllegalStateException("gate is already armed");
        }
        var newListeners = new JoinGateListeners(this, plugin, messages);
        plugin.getServer().getPluginManager().registerEvents(newListeners, plugin);
        this.listeners = newListeners;
    }

    // ---------- introspection for listeners and the pre-login refusal ----------

    /** The gate is active when enabled in config and not closed. */
    public boolean isEnabled() {
        return config.enabled() && !closed.get();
    }

    /** True while the player is held in the restore window. */
    public boolean isRestoring(UUID playerId) {
        if (!isEnabled()) {
            return false;
        }
        GateSession session = sessions.get(playerId);
        return session != null && session.isRestoring();
    }

    /** True while the player is refused logins by the restore-failure cooldown. */
    public boolean isRefused(UUID playerId) {
        return failures.isRefused(playerId);
    }

    /** Seconds left in the refusal; 0 when not refused. Diagnostics only. */
    public long refusedSecondsRemaining(UUID playerId) {
        return failures.refusedMillisRemaining(playerId) / 1000;
    }

    /** True when the storage probe says snapshots cannot be restored right now. */
    public boolean isStorageDegraded() {
        try {
            return storageProbe.status() == StorageStatus.DEGRADED;
        } catch (Exception e) {
            // a probe that cannot answer is not evidence of health
            LOGGER.error("storage probe threw; treating storage as DEGRADED", e);
            return true;
        }
    }

    /** Whether logins are refused while storage is degraded (config gate.disallow-on-storage-degraded). */
    public boolean disallowOnStorageDegraded() {
        return config.disallowOnStorageDegraded();
    }

    // ---------- public restore API ----------

    /**
     * Holds the player and starts the restore. Completes with {@link RestoreOutcome.Ready}
     * when the restore lifts, or {@link RestoreOutcome.Failed} when it fails or times out.
     *
     * <p>When no restore handler is installed the player is not held at all: the
     * future completes immediately and the join proceeds.</p>
     */
    public CompletableFuture<RestoreOutcome> enqueueRestore(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        if (!isEnabled()) {
            // gate disabled: vanilla login flow, nothing held
            return CompletableFuture.completedFuture(new RestoreOutcome.Ready());
        }
        GateSession stale = sessions.remove(playerId);
        if (stale != null) {
            // rejoined without a quit event in between — settle the old hold first
            LOGGER.warn("stale gate session for player {} replaced by a new join", playerId);
            cancelTimeout(stale);
            stale.restoring.set(false);
            stale.future.complete(new RestoreOutcome.Failed("superseded"));
        }
        GateSession session = new GateSession(playerId, player.getGameMode(), player.isMarkedInvulnerable());
        sessions.put(playerId, session);

        RestoreHandler handler = this.restoreHandler;
        if (handler == null) {
            // nothing wired to restore — lift immediately rather than hold the player hostage
            LOGGER.info("no restore handler installed; join gate lifts immediately for {} (restore is not running)", player.getName());
            session.restoring.set(false);
            sessions.remove(playerId, session);
            session.future.complete(new RestoreOutcome.Ready());
            return session.future;
        }
        runOnEntityThread(player, () -> {
            applyFreeze(player);
            player.sendMessage(messages.renderForPlayer(player, "player.loading"));
            armTimeout(session);
            try {
                handler.restore(player);
            } catch (Throwable t) {
                LOGGER.error("restore handler threw while dispatching for {}", player.getName(), t);
                markFailed(playerId, "handler-failed");
            }
        }, session);
        return session.future;
    }

    /** Marks the restore as complete; lifts the hold. Safe to call from any thread. */
    public void markReady(UUID playerId) {
        GateSession session = sessions.get(playerId);
        if (session == null) {
            LOGGER.warn("markReady for {} with no active gate session; ignoring", playerId);
            return;
        }
        cancelTimeout(session);
        Player player = resolve(playerId);
        if (player == null) {
            // player already gone — nothing to unfreeze, just settle the bookkeeping
            session.restoring.set(false);
            failures.recordSuccess(playerId);
            sessions.remove(playerId, session);
            session.future.complete(new RestoreOutcome.Ready());
            LOGGER.info("restore completed for {} (player already left)", playerId);
            return;
        }
        runOnEntityThread(player, () -> lift(session, player, true), session);
    }

    /**
     * Marks the restore as failed: kicks the player and records the failure.
     * After {@code gate.max-failures} consecutive failures the player is refused logins
     * for {@code gate.failure-cooldown-minutes}. {@code reason} is diagnostic-only and
     * never shown to the player. Safe to call from any thread.
     */
    public void markFailed(UUID playerId, String reason) {
        String effectiveReason = (reason == null || reason.isBlank()) ? "unknown" : reason;
        GateSession session = sessions.get(playerId);
        if (session == null) {
            LOGGER.warn("markFailed for {} with no active gate session; recording the failure anyway (reason={})", playerId, effectiveReason);
            failures.recordFailure(playerId, config.maxFailures(), cooldown());
            return;
        }
        cancelTimeout(session);
        Player player = resolve(playerId);
        if (player == null) {
            finishFailed(session, null, effectiveReason);
            return;
        }
        runOnEntityThread(player, () -> finishFailed(session, player, effectiveReason), session);
    }

    /** Player quit while gated: close the session without counting a failure or kicking. */
    public void onQuit(UUID playerId) {
        GateSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        cancelTimeout(session);
        session.restoring.set(false);
        session.future.complete(new RestoreOutcome.Failed("quit"));
        LOGGER.info("player {} left during the restore window; gate session closed without failure", playerId);
    }

    /**
     * Re-anchors the respawn location to the configured spawn policy and re-applies
     * the freeze — the vanilla respawn resets the gamemode, so the hold must be
     * re-applied after it lands.
     *
     * <p>Both effects are applied only while the player is gated. Players who are
     * not held get the vanilla respawn: their bed, respawn anchor or the
     * platform's respawn location — the spawn policy is a join-time decision and
     * must not leak into respawns after the restore lifted.</p>
     */
    @SuppressWarnings({"deprecation", "removal"})
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!isEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!isRestoring(playerId)) {
            return;
        }
        try {
            Location target = spawnPolicy.decide(new SpawnContext(playerId, player.getName(), false));
            if (target != null && target.getWorld() != null) {
                // deprecated-for-removal with no replacement in 26.3 — the only setter that exists
                event.setRespawnLocation(target);
            }
        } catch (Exception e) {
            LOGGER.error("spawn policy threw during respawn for {}; keeping the platform's respawn location", playerId, e);
        }
        // the respawn wipes the gamemode — re-freeze right after it applies
        player.getScheduler().execute(plugin, () -> applyFreeze(player), () -> { }, 1L);
    }

    // ---------- spawn decision (async spawn-location event) ----------

    /**
     * Decides where the joining player appears. Runs on the async spawn-decision
     * thread; never touches entities. A policy failure keeps the platform's spawn —
     * a spawn decision is not worth failing a login over.
     *
     * <p>The profile accessors used here are deprecated for removal, but they are the
     * only way the connection exposes the player id in 26.3 — there is no alternative
     * public API for it.</p>
     */
    @SuppressWarnings({"deprecation", "removal"})
    public void decideSpawn(AsyncPlayerSpawnLocationEvent event) {
        if (!isEnabled()) {
            return;
        }
        UUID playerId = null;
        String playerName = "";
        try {
            PlayerConfigurationConnection connection = event.getConnection();
            if (connection != null) {
                PlayerProfile profile = connection.getProfile();
                if (profile != null) {
                    playerId = profile.getId();
                    if (profile.getName() != null) {
                        playerName = profile.getName();
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("could not read the joining player's profile; keeping the platform's spawn location", e);
        }
        if (playerId == null) {
            return;
        }
        try {
            Location target = spawnPolicy.decide(new SpawnContext(playerId, playerName, event.isNewPlayer()));
            if (target != null && target.getWorld() != null) {
                event.setSpawnLocation(target);
                LOGGER.debug("spawn for {} set by the spawn policy", playerId);
            }
        } catch (Exception e) {
            // spawn overriding must never block the login — fail open here, stay closed everywhere else
            LOGGER.error("spawn policy threw for {}; keeping the platform's spawn location", playerId, e);
        }
    }

    // ---------- shutdown ----------

    /**
     * Lifts the gate for everyone still held and unregisters the listeners.
     * No kicks on shutdown: held players are released with control restored.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        JoinGateListeners registered = this.listeners;
        if (registered != null) {
            try {
                org.bukkit.event.HandlerList.unregisterAll(registered);
            } catch (Exception e) {
                LOGGER.warn("could not unregister the join gate listeners cleanly", e);
            }
        }
        int released = 0;
        for (GateSession session : sessions.values()) {
            cancelTimeout(session);
            session.restoring.set(false);
            Player player = resolve(session.playerId);
            if (player != null) {
                runOnEntityThread(player, () -> applyUnfreeze(player, session), session);
                released++;
            }
            session.future.complete(new RestoreOutcome.Ready());
        }
        sessions.clear();
        LOGGER.info("join gate closed; {} held player(s) were released", released);
    }

    // ---------- internals ----------

    private Duration cooldown() {
        return Duration.ofMinutes(config.failureCooldownMinutes());
    }

    private @Nullable Player resolve(UUID playerId) {
        return plugin.getServer().getPlayer(playerId);
    }

    private void applyFreeze(Player player) {
        try {
            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            player.setVisibleByDefault(false);
            player.setInvulnerable(true);
        } catch (Exception e) {
            // the cancel-set still blocks gameplay even if the freeze did not fully apply
            LOGGER.error("could not fully freeze {}", player.getName(), e);
        }
    }

    private void applyUnfreeze(Player player, GateSession session) {
        try {
            player.setVisibleByDefault(true);
            player.setInvulnerable(session.previousInvulnerable);
            player.setGameMode(session.previousGameMode);
        } catch (Exception e) {
            LOGGER.error("could not fully unfreeze {}", player.getName(), e);
        }
    }

    private void lift(GateSession session, Player player, boolean notify) {
        applyUnfreeze(player, session);
        if (notify) {
            try {
                player.sendMessage(messages.renderForPlayer(player, "player.restored"));
            } catch (Exception e) {
                LOGGER.warn("could not send the restore confirmation to {}", player.getName(), e);
            }
        }
        session.restoring.set(false);
        failures.recordSuccess(session.playerId);
        sessions.remove(session.playerId, session);
        session.future.complete(new RestoreOutcome.Ready());
        LOGGER.info("player {} restored; gate lifted", player.getName());
    }

    private void finishFailed(GateSession session, @Nullable Player player, String reason) {
        FailureTracker.FailureResult failure = failures.recordFailureDetailed(session.playerId, config.maxFailures(), cooldown());
        boolean tripped = failure.tripped();
        if (player != null) {
            try {
                Component message;
                if (failure.streak() > 0) {
                    message = messages.renderForPlayer(player, "player.restore-failed-kick",
                            TypedArgument.number("failures", failure.streak()),
                            TypedArgument.number("limit", config.maxFailures()));
                } else {
                    // overflow path: the tracker could not count it — no made-up numbers in the message
                    message = messages.renderForPlayer(player, "player.restore-failed");
                }
                player.kick(message);
            } catch (Exception e) {
                LOGGER.error("could not kick {} after a failed restore", session.playerId, e);
            }
        }
        session.restoring.set(false);
        sessions.remove(session.playerId, session);
        session.future.complete(new RestoreOutcome.Failed(reason));
        LOGGER.info("restore failed for {} ({}){}", session.playerId, reason, tripped ? "; logins refused" : "");
    }

    private void cancelTimeout(GateSession session) {
        var task = session.timeoutTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void armTimeout(GateSession session) {
        int seconds = config.restoreTimeoutSeconds();
        if (seconds <= 0) {
            return;
        }
        try {
            session.timeoutTask = timer.schedule(() -> onTimeout(session.playerId), seconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            LOGGER.error("could not arm the restore timeout for {}; the restore will not time out", session.playerId, e);
        }
    }

    private void onTimeout(UUID playerId) {
        GateSession session = sessions.get(playerId);
        if (session == null || !session.isRestoring()) {
            return;
        }
        LOGGER.warn("restore timed out for {} after {}s; kicking", playerId, config.restoreTimeoutSeconds());
        markFailed(playerId, "timeout");
    }

    /** Runs {@code task} on the entity's owning thread now, or hops to it. */
    private void runOnEntityThread(Player player, Runnable task, GateSession session) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            task.run();
            return;
        }
        player.getScheduler().execute(plugin, task, () -> {
            // entity retired (player left/region unloaded) before the task ran — settle the session
            session.restoring.set(false);
            sessions.remove(session.playerId, session);
            session.future.complete(new RestoreOutcome.Failed("quit"));
        }, 0L);
    }

    private void reapQuietly() {
        try {
            failures.reap();
        } catch (Throwable t) {
            LOGGER.warn("failure tracker reaper threw", t);
        }
    }
}
