package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.event.ContinuumModuleCaptureEvent;
import lol.candyriya.continuum.api.event.ContinuumFailureEvent;
import lol.candyriya.continuum.api.event.ContinuumSaveEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionCreatedEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionReleasedEvent;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.report.CaptureReport;
import lol.candyriya.continuum.api.report.ModuleCodes;
import lol.candyriya.continuum.api.report.ModuleResult;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotSchema;
import lol.candyriya.continuum.storage.api.PlayerSnapshot;
import lol.candyriya.continuum.storage.api.PlayerStorage;
import lol.candyriya.continuum.storage.api.SaveOptions;
import lol.candyriya.continuum.storage.api.SaveResult;
import lol.candyriya.continuum.storage.api.SessionRecord;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * The capture pipeline: everything that turns a live player into a persisted
 * snapshot.
 *
 * <p>Triggers: quit (final), pre-transfer request (before the exchange), the
 * autosave timer ({@code sync.autosave.*}), shutdown (best effort), and the
 * manual/API surface ({@link #capture} with any reason). Captures run their
 * module work on the player's entity thread — module {@code capture} calls are
 * entity-affine — while envelope assembly, (de)serialization and every DB
 * round trip happen off it, fenced by the session token and serialized per
 * player through {@link PlayerFifoQueue}.</p>
 *
 * <p><b>Incremental saves.</b> Non-authoritative triggers (autosave) persist
 * only what changed: modules in the dirty-tracked set are re-captured only
 * when dirty (or missing from the last-known-good envelope); all other enabled
 * modules are carried over from that envelope. Quit, transfer, shutdown and
 * manual captures are always full. There is no such thing as a partial write —
 * the envelope written always contains every enabled module either fresh or
 * carried over, so a capture never erases sibling modules from the row.</p>
 *
 * <p><b>Failure handling.</b> Optional module failure degrades the report to
 * {@code PARTIAL}; required module failure aborts the persist entirely
 * (fail-closed: nothing is written, the previous row stays). A storage result
 * of {@code StaleToken} is logged loudly, fired as a failure event and drops
 * the last-known-good bookmark — never silent.</p>
 *
 * <p><b>Events.</b> Fires {@link ContinuumSaveEvent} before anything (veto
 * honored), one {@link ContinuumModuleCaptureEvent} per module, and
 * {@link ContinuumFailureEvent} on persistence-level failures. Dispatch is a
 * no-op until something subscribes through the platform's event service.</p>
 */
public final class SnapshotManager implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SnapshotManager.class);
    private static final JsonMapper MAPPER = JacksonMappers.create();

    /** The dirty-tracked module ids: the expensive modules whose re-capture dominates an autosave. */
    static final List<String> TRACKED = List.of(
            BuiltinModules.INVENTORY,
            BuiltinModules.ENDERCHEST,
            BuiltinModules.VITALS,
            BuiltinModules.EXPERIENCE,
            BuiltinModules.EFFECTS,
            BuiltinModules.ATTRIBUTES);

    private final Plugin plugin;
    private final ContinuumConfig config;
    private final ModuleRuntime modules;
    private final PlayerFifoQueue fifo;
    private final DirtyTracker dirty;
    private final SessionRegistry sessions;
    private final PlayerStorage storage;
    private final StorageHealth storageHealth;
    private final EventDispatcher events;
    private final OrchestrationSettings settings;
    private final Predicate<java.util.UUID> isRestoring;
    private final String backendId;
    private final LastKnownGoodStore lastKnownGood;

    private final ScheduledExecutorService autosave;
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean autosaveDegradedWarned;

    public SnapshotManager(Plugin plugin,
                           ContinuumConfig config,
                           ModuleRuntime modules,
                           PlayerFifoQueue fifo,
                           DirtyTracker dirty,
                           SessionRegistry sessions,
                           PlayerStorage storage,
                           StorageHealth storageHealth,
                           EventDispatcher events,
                           OrchestrationSettings settings,
                           Predicate<java.util.UUID> isRestoring,
                           LastKnownGoodStore lastKnownGood) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.fifo = Objects.requireNonNull(fifo, "fifo");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.storageHealth = Objects.requireNonNull(storageHealth, "storageHealth");
        this.events = Objects.requireNonNull(events, "events");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.isRestoring = Objects.requireNonNull(isRestoring, "isRestoring");
        this.lastKnownGood = Objects.requireNonNull(lastKnownGood, "lastKnownGood");
        this.backendId = config.network().serverId();
        this.autosave = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("continuum-autosave-", 0).factory());
    }

    /** Schedules the autosave sweep per {@code sync.autosave.interval}. Idempotent. */
    public void start() {
        if (closed.get()) {
            return;
        }
        Duration interval = config.sync().autosave().interval();
        if (!config.sync().autosave().enabled()) {
            LOGGER.info("autosave disabled in config; not scheduling snapshot sweeps");
            return;
        }
        autosave.scheduleWithFixedDelay(this::sweepQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        LOGGER.info("autosave sweep scheduled every {}", interval);
    }

    // ---------- public capture surface ----------

    /**
     * Captures the player. Folds incremental capture into a full one when the
     * reason is not {@code AUTOSAVE}. The returned future completes with the
     * capture report; persistence failures surface as {@code FAILED} reports
     * with no {@code snapshotId}, never as silent drops.
     */
    public CompletableFuture<CaptureReport> capture(Player player, SnapshotReason reason) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        CompletableFuture<CaptureReport> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("snapshot manager is closed"));
            return result;
        }
        java.util.UUID playerId = player.getUniqueId();

        // never persist a half-restore: a frozen spectator mid-restore is not a state to keep
        if (isRestoring.test(playerId)) {
            LOGGER.info("capture for {} skipped: restore in flight (reason={})", playerId, reason);
            return completed(result, report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
        }

        if (reason == SnapshotReason.AUTOSAVE) {
            if (!config.sync().autosave().enabled()) {
                return completed(result, report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
            }
            if (!storageHealth.isUp()) {
                if (!autosaveDegradedWarned) {
                    LOGGER.warn("storage is degraded; skipping autosave sweeps until it recovers (warn-once)");
                    autosaveDegradedWarned = true;
                }
                return completed(result, report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
            }
            autosaveDegradedWarned = false;
        }
        if (gameThread(player)) {
            beginCapture(player, reason, reason != SnapshotReason.AUTOSAVE, result);
        } else {
            player.getScheduler().execute(plugin,
                    () -> beginCapture(player, reason, reason != SnapshotReason.AUTOSAVE, result),
                    () -> result.completeExceptionally(
                            new IllegalStateException("player left before the capture could start")),
                    0L);
        }
        return result;
    }

    /** Final capture on quit; always full. */
    public CompletableFuture<CaptureReport> captureFinal(Player player) {
        return capture(player, SnapshotReason.DISCONNECT);
    }

    /** Serialize the full current state regardless of dirty flags or autosave toggles. */
    public CompletableFuture<CaptureReport> captureTransfer(Player player) {
        return capture(player, SnapshotReason.TRANSFER);
    }

    /** Manual/API capture surface. */
    public CompletableFuture<CaptureReport> captureManual(Player player, SnapshotReason reason) {
        Objects.requireNonNull(reason, "reason");
        return capture(player, reason);
    }

    /**
     * Releases the fenced session after the quit capture. Queued behind the
     * capture on the same per-player lane, so the final write and the release
     * happen in order. No-op when this backend holds no session.
     */
    public CompletableFuture<Boolean> releaseSession(java.util.UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        SessionRecord session = sessions.remove(playerId);
        if (session == null) {
            return CompletableFuture.completedFuture(false);
        }
        return fifo.submit(playerId, () -> {
            boolean released = storage.releaseSession(playerId, session.token());
            if (released) {
                events.dispatch(new ContinuumSessionReleasedEvent(SessionRegistry.releasedInfo(session)));
            } else {
                LOGGER.debug("session release for {} returned false (ownership already moved elsewhere)", playerId);
            }
            lastKnownGood.remove(playerId);
            return released;
        });
    }

    /** The current last-known-good snapshot for a player, or null. Diagnostics. */
    public PlayerSnapshot lastKnownGood(java.util.UUID playerId) {
        return lastKnownGood.get(Objects.requireNonNull(playerId, "playerId"));
    }

    // ---------- internal pipeline ----------

    private void beginCapture(Player player,
                              SnapshotReason reason,
                              boolean full,
                              CompletableFuture<CaptureReport> result) {
        java.util.UUID playerId = player.getUniqueId();
        try {
            ContinuumSaveEvent saveEvent = new ContinuumSaveEvent(playerId, reason);
            events.dispatch(saveEvent);
            if (saveEvent.isCancelled()) {
                LOGGER.info("capture vetoed by event listener for {} (reason={}, cancelReason={})",
                        playerId, reason, saveEvent.cancelReason());
                result.complete(report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
                return;
            }

            List<ModuleRuntime.RegisteredModule> enabled = modules.planOrder();
            if (enabled.isEmpty()) {
                LOGGER.info("capture for {} skipped: no modules enabled in config", playerId);
                result.complete(report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
                return;
            }

            SnapshotEnvelope lkgEnvelope = lkgEnvelope(playerId);
            List<ModuleRuntime.RegisteredModule> toCapture = selectToCapture(enabled, full, lkgEnvelope, playerId);
            if (toCapture.isEmpty()) {
                LOGGER.debug("autosave for {} skipped: nothing changed", playerId);
                result.complete(report(playerId, null, reason, SyncResult.SUCCESS, List.of(), Instant.now()));
                return;
            }
            // flags set while this capture is in flight must survive it — remember the starting set
            Set<String> dirtyAtStart = dirty.dirtyModules(playerId);

            List<CaptureJob> jobs = new ArrayList<>(toCapture.size());
            for (ModuleRuntime.RegisteredModule module : toCapture) {
                long started = System.nanoTime();
                CompletionStage<?> stage;
                try {
                    stage = module.module().capture(player);
                } catch (Throwable t) {
                    stage = CompletableFuture.failedFuture(t);
                }
                if (stage == null) {
                    stage = CompletableFuture.failedFuture(new IllegalStateException("module returned a null stage"));
                }
                // bound every stage exactly once — the finish pass joins these and they always complete
                CompletableFuture<?> bounded = stage.toCompletableFuture()
                        .orTimeout(module.timeoutMillis(settings.moduleIoTimeout().toMillis()), TimeUnit.MILLISECONDS);
                jobs.add(new CaptureJob(module, bounded, started));
            }

            CompletableFuture<?>[] futures = jobs.stream()
                    .map(CaptureJob::stage)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).whenComplete((ignored, err) -> {
                try {
                    finishCapture(playerId, reason, enabled, jobs, lkgEnvelope, dirtyAtStart, result);
                } catch (Throwable t) {
                    LOGGER.error("finishing the capture failed for {}", playerId, t);
                    result.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            LOGGER.error("capture could not start for {}", playerId, t);
            result.completeExceptionally(t);
        }
    }

    private void finishCapture(java.util.UUID playerId,
                               SnapshotReason reason,
                               List<ModuleRuntime.RegisteredModule> enabled,
                               List<CaptureJob> jobs,
                               SnapshotEnvelope lkgEnvelope,
                               Set<String> dirtyAtStart,
                               CompletableFuture<CaptureReport> result) {
        List<SnapshotAssembler.CapturedPayload> captured = new ArrayList<>(jobs.size());
        List<ModuleResult> rows = new ArrayList<>(jobs.size());
        boolean requiredFailed = false;
        boolean anyFailed = false;

        for (CaptureJob job : jobs) {
            long millis = millisSince(job.startedNanos());
            ModuleRuntime.RegisteredModule module = job.module();
            Throwable error = null;
            Object payload = null;
            try {
                payload = job.stage().join();
            } catch (CompletionException e) {
                error = e.getCause() != null ? e.getCause() : e;
            } catch (Throwable t) {
                error = t;
            }

            if (error == null) {
                JsonNode node = payload == null ? null : MAPPER.valueToTree(payload);
                if (node == null || !node.isObject()) {
                    error = new IllegalStateException("module produced a non-object payload of type "
                            + (payload == null ? "null" : payload.getClass().getName()));
                } else {
                    captured.add(new SnapshotAssembler.CapturedPayload(module.id(), module.module().version(), node));
                    rows.add(ModuleResult.ok(module.id(), millis));
                    continue;
                }
            }

            String code = captureFailureCode(error);
            rows.add(ModuleResult.failed(module.id(), code, detailOf(error), millis));
            anyFailed = true;
            if (module.failurePolicy() == FailurePolicy.ABORT) {
                requiredFailed = true;
            }
        }

        for (ModuleResult row : rows) {
            events.dispatch(new ContinuumModuleCaptureEvent(playerId, row));
        }

        if (requiredFailed) {
            LOGGER.warn("capture for {} aborted: a required module failed (no snapshot written)", playerId);
            markAllDirty(playerId, enabled);
            result.complete(report(playerId, null, reason, SyncResult.FAILED, rows, Instant.now()));
            return;
        }

        List<String> enabledIds = enabled.stream().map(ModuleRuntime.RegisteredModule::id).toList();
        SnapshotEnvelope merged;
        try {
            merged = SnapshotAssembler.merge(playerId, Instant.now(), enabledIds, captured, lkgEnvelope);
        } catch (RuntimeException e) {
            LOGGER.error("assembling the snapshot envelope failed for {}", playerId, e);
            markAllDirty(playerId, enabled);
            result.complete(report(playerId, null, reason, SyncResult.FAILED, rows, Instant.now()));
            return;
        }
        if (merged == null) {
            // only optional modules failed and there is no baseline to carry over — nothing to persist
            SyncResult verdict = anyFailed ? SyncResult.PARTIAL : SyncResult.SUCCESS;
            result.complete(report(playerId, null, reason, verdict, rows, Instant.now()));
            return;
        }

        SyncResult verdict = anyFailed ? SyncResult.PARTIAL : SyncResult.SUCCESS;
        fifo.submit(playerId, () -> persist(playerId, merged, reason))
                .whenComplete((outcome, err) -> {
                    if (err != null) {
                        Throwable root = unwrap(err);
                        LOGGER.error("snapshot save failed for {}", playerId, root);
                        events.dispatch(new ContinuumFailureEvent(playerId, null, "SAVE_FAILED", String.valueOf(root.getMessage())));
                        markAllDirty(playerId, enabled);
                        result.complete(report(playerId, null, reason, SyncResult.FAILED, rows, Instant.now()));
                        return;
                    }
                    if (outcome.saved) {
                        lastKnownGood.put(playerId, outcome.snapshot);
                        // clear only what this round re-read; flags set during the flight stay dirty
                        Set<String> persistedFresh = new java.util.HashSet<>();
                        for (ModuleResult row : rows) {
                            if (row.status() == lol.candyriya.continuum.api.report.ModuleStatus.OK) {
                                persistedFresh.add(row.moduleId());
                            }
                        }
                        Set<String> clearable = new java.util.HashSet<>(dirtyAtStart);
                        clearable.retainAll(persistedFresh);
                        dirty.clear(playerId, clearable);
                        result.complete(report(playerId, outcome.historyId, reason, verdict, rows, Instant.now()));
                        return;
                    }
                    // failure — nothing landed; refuse to trust the bookmark and force a full next capture
                    markAllDirty(playerId, enabled);
                    if (outcome.currentFencingToken != null) {
                        lastKnownGood.remove(playerId);
                        events.dispatch(new ContinuumFailureEvent(playerId, null, "STALE_TOKEN",
                                "save rejected: stored fencing token is " + outcome.currentFencingToken));
                    } else {
                        events.dispatch(new ContinuumFailureEvent(playerId, null, "SAVE_FAILED",
                                outcome.errorCode != null ? outcome.errorCode : "unknown"));
                    }
                    result.complete(report(playerId, null, reason, SyncResult.FAILED, rows, Instant.now()));
                });
    }

    /** Runs inside the FIFO: the only place that touches storage for a save. */
    private PersistOutcome persist(java.util.UUID playerId, SnapshotEnvelope envelope, SnapshotReason reason) {
        SessionRecord session = sessions.get(playerId);
        if (session == null) {
            try {
                session = storage.acquireSession(playerId, backendId);
            } catch (StorageException e) {
                LOGGER.error("could not acquire a session for {} at capture time; snapshot NOT written", playerId, e);
                return PersistOutcome.error("session-acquire-failed");
            }
            sessions.put(session);
            events.dispatch(new ContinuumSessionCreatedEvent(SessionRegistry.toApiInfo(session)));
        }
        long token = session.token();
        SaveOptions options = new SaveOptions(reason.name(), backendId,
                List.of(OutboxPayloads.snapshotUpdated(playerId, token, SnapshotSchema.CURRENT_VERSION)));
        PlayerSnapshot snapshot = new PlayerSnapshot(envelope, token, Instant.now());
        try {
            SaveResult saveResult = storage.save(snapshot, token, options);
            if (saveResult instanceof SaveResult.Saved saved) {
                return PersistOutcome.saved(saved.historySnapshotId(), snapshot);
            } else if (saveResult instanceof SaveResult.StaleToken stale) {
                LOGGER.error("stale fencing token on save for {} (token {} rejected, stored token {}) — "
                        + "snapshot NOT written; this backend lost ownership, play on",
                        playerId, token, stale.currentFencingToken());
                return PersistOutcome.staleToken(stale.currentFencingToken());
            }
            return PersistOutcome.error("unexpected-save-result");
        } catch (StorageException e) {
            LOGGER.error("storage failed to save the snapshot for {}", playerId, e);
            return PersistOutcome.error("storage-error");
        }
    }

    private List<ModuleRuntime.RegisteredModule> selectToCapture(List<ModuleRuntime.RegisteredModule> enabled,
                                                                 boolean full,
                                                                 SnapshotEnvelope lkgEnvelope,
                                                                 java.util.UUID playerId) {
        if (full || lkgEnvelope == null) {
            return enabled;
        }
        List<ModuleRuntime.RegisteredModule> selection = new ArrayList<>(enabled.size());
        boolean anythingToDo = false;
        boolean lkgComplete = true;
        java.util.Set<String> dirtySet = dirty.dirtyModules(playerId);
        for (ModuleRuntime.RegisteredModule module : enabled) {
            boolean present = lkgEnvelope.modules().stream().anyMatch(ms -> ms.moduleId().equals(module.id()));
            if (!present) {
                lkgComplete = false;
            }
            if (TRACKED.contains(module.id())) {
                // dirty, or never captured before — re-capture it
                if (!present || dirtySet.contains(module.id())) {
                    selection.add(module);
                    anythingToDo = true;
                }
            }
        }
        // a fully covered LKG with nothing tracked dirty: an autosave would write
        // what is already stored — skip the write entirely. (statistics are absolute
        // counters, so the carry-over path is always consistent; no deltas to strand)
        if (lkgComplete && !anythingToDo) {
            return List.of();
        }
        for (ModuleRuntime.RegisteredModule module : enabled) {
            if (!TRACKED.contains(module.id())) {
                // cheap, infrequently-shared modules always ride along fresh on a writing autosave
                selection.add(module);
            }
        }
        return selection;
    }

    private SnapshotEnvelope lkgEnvelope(java.util.UUID playerId) {
        PlayerSnapshot snapshot = lastKnownGood.get(playerId);
        return snapshot == null ? null : snapshot.envelope();
    }

    private void markAllDirty(java.util.UUID playerId, List<ModuleRuntime.RegisteredModule> enabled) {
        dirty.markAllDirty(playerId, enabled.stream().map(ModuleRuntime.RegisteredModule::id).toList());
    }

    private boolean gameThread(Player player) {
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable t) {
            // no platform answer (e.g. outside a live server) — take the hop path
            return false;
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (Throwable t) {
            LOGGER.warn("autosave sweep blew up; will retry next interval", t);
        }
    }

    private void sweep() {
        if (closed.get()) {
            return;
        }
        if (!config.sync().autosave().enabled() || !storageHealth.isUp()) {
            return;
        }
        // roster read belongs on the global lane
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            if (closed.get()) {
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                java.util.UUID playerId = player.getUniqueId();
                try {
                    if (!isRestoring.test(playerId)) {
                        capture(player, SnapshotReason.AUTOSAVE);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("autosave for {} failed to enqueue", player.getName(), t);
                }
            }
        });
    }

    /** Best-effort capture of every online player, then session release. Bounded by settings. */
    public void shutdownCaptureAndRelease() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) {
            return;
        }
        LOGGER.info("backend shutdown: capturing {} online player(s) on a best-effort basis", online.size());
        List<CompletableFuture<?>> captures = new ArrayList<>(online.size());
        for (Player player : online) {
            captures.add(capture(player, SnapshotReason.SHUTDOWN)
                    .exceptionally(err -> {
                        LOGGER.error("shutdown capture failed for {}", player.getUniqueId(), unwrap(err));
                        return null;
                    }));
        }
        try {
            CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new))
                    .orTimeout(settings.shutdownCaptureBudget().toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            // budget exceeded or a capture blew up — reported per-player above; carry on
            LOGGER.warn("shutdown capture did not fully finish within {}; proceeding with shutdown",
                    settings.shutdownCaptureBudget());
        }
        for (Player player : online) {
            try {
                releaseSession(player.getUniqueId());
            } catch (Throwable t) {
                LOGGER.error("shutdown session release failed for {}", player.getUniqueId(), t);
            }
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        autosave.shutdownNow();
        try {
            // capture still accepts calls here — the shutdown capture needs them
            shutdownCaptureAndRelease();
        } catch (Throwable t) {
            LOGGER.error("shutdown capture blew up; state left to the last saved snapshot", t);
        } finally {
            closed.set(true);
        }
        LOGGER.info("snapshot manager closed");
    }

    // ---------- helpers ----------

    private static CompletableFuture<CaptureReport> completed(CompletableFuture<CaptureReport> target, CaptureReport report) {
        target.complete(report);
        return target;
    }

    private static long millisSince(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String captureFailureCode(Throwable error) {
        Throwable root = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return root instanceof TimeoutException ? ModuleCodes.TIMEOUT : ModuleCodes.CAPTURE_FAILED;
    }

    private static String detailOf(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.toString() : message;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static CaptureReport report(java.util.UUID playerId,
                                        Long snapshotId,
                                        SnapshotReason reason,
                                        SyncResult result,
                                        List<ModuleResult> rows,
                                        Instant completedAt) {
        return new CaptureReport(playerId, snapshotId, reason, result, rows, completedAt);
    }

    private record CaptureJob(ModuleRuntime.RegisteredModule module,
                              CompletableFuture<?> stage,
                              long startedNanos) {
    }

    /** Internal result of the FIFO-persisted save; folded into the capture report by the caller. */
    private record PersistOutcome(boolean saved,
                                  Long historyId,
                                  PlayerSnapshot snapshot,
                                  Long currentFencingToken,
                                  String errorCode) {

        static PersistOutcome saved(long historyId, PlayerSnapshot snapshot) {
            return new PersistOutcome(true, historyId, snapshot, null, null);
        }

        static PersistOutcome staleToken(long currentFencingToken) {
            return new PersistOutcome(false, null, null, currentFencingToken, "stale-token");
        }

        static PersistOutcome error(String errorCode) {
            return new PersistOutcome(false, null, null, null, errorCode);
        }
    }
}
