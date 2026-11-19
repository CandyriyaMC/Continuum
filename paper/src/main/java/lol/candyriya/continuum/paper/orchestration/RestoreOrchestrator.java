package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.event.ContinuumFailureEvent;
import lol.candyriya.continuum.api.event.ContinuumLoadEvent;
import lol.candyriya.continuum.api.event.ContinuumModuleRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionCreatedEvent;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.report.ModuleCodes;
import lol.candyriya.continuum.api.report.ModuleResult;
import lol.candyriya.continuum.api.report.RestoreReport;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.internal.JacksonMappers;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotMigrationRunner;
import lol.candyriya.continuum.paper.gate.RestoreGate;
import lol.candyriya.continuum.paper.gate.RestoreHandler;
import lol.candyriya.continuum.storage.api.PlayerSnapshot;
import lol.candyriya.continuum.storage.api.PlayerStorage;
import lol.candyriya.continuum.storage.api.SessionRecord;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The join-gate side of the sync pipeline: restores a stored snapshot onto a
 * joining player.
 *
 * <p>Flow: gate enqueues the player (already frozen) → token is acquired in the
 * database through the per-player FIFO → the snapshot is loaded and run through
 * the {@code SnapshotMigrationRunner} (a no-op for current-schema payloads; the
 * seam for schema bumps) → the session moves to {@code LOADING} → modules run
 * on the player's entity thread in plan order → per-module failure isolation
 * per the {@link FailurePolicy} semantics ({@code ABORT} for load-bearing
 * modules, {@code SKIP} for the rest) → the gate is marked ready or failed
 * exactly once.</p>
 *
 * <p><b>Never wedges the gate.</b> Every module call is bounded by a timeout
 * ({@code moduleIoTimeout}, or the adapter's own for third-party adapters),
 * the whole restore is bounded by {@code restoreBudget} (modules still
 * unaccounted for when it expires are reported TIMEOUT and the restore
 * finalizes with what it has — hung required modules fail the restore, hung
 * optional ones degrade it to PARTIAL), every exceptional path resolves to
 * {@code markReady} or {@code markFailed}, and a player that leaves mid-restore
 * settles the gate session as failed. If the gate's own restore timeout fires
 * first, late completions are ignored by the gate itself.</p>
 *
 * <p><b>Optional vs required.</b> Optional module failure degrades the report to
 * {@code PARTIAL} and the restore completes; required module failure aborts the
 * remaining modules, marks the session {@code RECOVERY_REQUIRED} and the gate
 * kicks the player — the saved state is left untouched for the recovery
 * flow.</p>
 */
public final class RestoreOrchestrator implements RestoreHandler, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestoreOrchestrator.class);
    private static final JsonMapper MAPPER = JacksonMappers.create();

    private final RestoreGate gate;
    private final Plugin plugin;
    private final ContinuumConfig config;
    private final ModuleRuntime modules;
    private final PlayerFifoQueue fifo;
    private final DirtyTracker dirty;
    private final SessionRegistry sessions;
    private final PlayerStorage storage;
    private final EventDispatcher events;
    private final OrchestrationSettings settings;
    private final LastKnownGoodStore lastKnownGood;
    private final String backendId;
    private final SnapshotMigrationRunner migrations;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    public RestoreOrchestrator(RestoreGate gate,
                               Plugin plugin,
                               ContinuumConfig config,
                               ModuleRuntime modules,
                               PlayerFifoQueue fifo,
                               DirtyTracker dirty,
                               SessionRegistry sessions,
                               PlayerStorage storage,
                               EventDispatcher events,
                               OrchestrationSettings settings,
                               LastKnownGoodStore lastKnownGood,
                               SnapshotMigrationRunner migrations) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.fifo = Objects.requireNonNull(fifo, "fifo");
        this.dirty = Objects.requireNonNull(dirty, "dirty");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.events = Objects.requireNonNull(events, "events");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lastKnownGood = Objects.requireNonNull(lastKnownGood, "lastKnownGood");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        this.backendId = config.network().serverId();
    }

    /** Called by the gate on the player's entity thread; must return promptly — the work is off-thread. */
    @Override
    public void restore(Player player) {
        java.util.UUID playerId = player.getUniqueId();
        if (closed.get()) {
            LOGGER.warn("restore requested for {} but the orchestrator is closed; failing the gate", playerId);
            gate.markFailed(playerId, "orchestrator-closed");
            return;
        }
        try {
            ContinuumLoadEvent loadEvent = new ContinuumLoadEvent(playerId);
            events.dispatch(loadEvent);
            if (loadEvent.isCancelled()) {
                LOGGER.warn("restore vetoed by event listener for {} ({}); admitting with default state",
                        playerId, loadEvent.cancelReason());
                gate.markReady(playerId);
                return;
            }
            fifo.submit(playerId, () -> fetch(playerId))
                    .whenComplete((fetched, err) -> {
                        if (err != null) {
                            Throwable root = unwrap(err);
                            LOGGER.error("session fetch failed for {}; failing the restore", playerId, root);
                            String reason = root.getMessage() == null || root.getMessage().isBlank()
                                    ? "restore-fetch-failed" : root.getMessage();
                            finishFailed(playerId, reason, sessions.get(playerId));
                            return;
                        }
                        if (fetched == null) {
                            // brand-new player with no snapshot row anywhere
                            LOGGER.info("no snapshot for {} — fresh player, restore trivially clean", playerId);
                            transitionSessionQuietly(playerId, sessions.get(playerId), SessionState.ACTIVE);
                            gate.markReady(playerId);
                            return;
                        }
                        // the fifo pool is not the entity lane — always hop; hopping from the
                        // entity lane would still be correct (execute() dispatches to it)
                        hopToEntityLane(player, () -> applyRestore(player, fetched));
                    });
        } catch (Throwable t) {
            LOGGER.error("restore could not start for {}", playerId, t);
            try {
                gate.markFailed(playerId, "handler-failed");
            } catch (Throwable inner) {
                LOGGER.error("gate refused the failure mark for {}", playerId, inner);
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }

    // ---------- fetch (FIFO, storage lane) ----------

    private FetchOutcome fetch(java.util.UUID playerId) throws RestoreAbortException {
        SessionRecord session;
        try {
            session = storage.acquireSession(playerId, backendId);
        } catch (StorageException e) {
            LOGGER.error("could not acquire a session for {} during restore", playerId, e);
            throw new RestoreAbortException("session-acquire-failed", e);
        }
        sessions.put(session);
        events.dispatch(new ContinuumSessionCreatedEvent(SessionRegistry.toApiInfo(session)));
        // inside the FIFO already; keep it a plain fenced call, not a nested queue hop
        try {
            storage.transitionSession(playerId, session.token(), SessionState.LOADING);
        } catch (StorageException e) {
            LOGGER.warn("could not move session for {} to LOADING; continuing with the restore", playerId, e);
        }
        try {
            var loaded = storage.load(playerId);
            if (loaded.isEmpty()) {
                return null;
            }
            return new FetchOutcome(session, migrate(loaded.get()));
        } catch (StorageException e) {
            LOGGER.error("could not load the snapshot for {} during restore", playerId, e);
            throw new RestoreAbortException("storage-load-failed", e);
        }
    }

    /**
     * Runs the envelope through the registered migration chain. The storage
     * layer decodes bytes it wrote itself; anything older crosses here. No
     * schema steps exist yet (schema version is 1), so this is validation plus
     * the extension seam for when the schema bumps — never a silent accept.
     */
    private PlayerSnapshot migrate(PlayerSnapshot snapshot) throws RestoreAbortException {
        try {
            JsonNode tree = MAPPER.valueToTree(snapshot.envelope());
            JsonNode migrated = migrations.migrate(tree);
            SnapshotEnvelope envelope = MAPPER.treeToValue(migrated, SnapshotEnvelope.class);
            if (envelope == null) {
                throw new RestoreAbortException("snapshot-migration-failed",
                        new IllegalStateException("migrated payload decoded to null"));
            }
            return new PlayerSnapshot(envelope, snapshot.fencingToken(), snapshot.savedAt());
        } catch (RestoreAbortException e) {
            throw e;
        } catch (Exception e) {
            throw new RestoreAbortException("snapshot-migration-failed", e);
        }
    }

    // ---------- apply (entity lane) ----------

    private void applyRestore(Player player, FetchOutcome fetched) {
        java.util.UUID playerId = player.getUniqueId();
        SnapshotEnvelope envelope = fetched.snapshot().envelope();
        SessionRecord session = fetched.session();

        Map<String, ModuleSnapshot> stored = new HashMap<>();
        for (ModuleSnapshot module : envelope.modules()) {
            stored.put(module.moduleId(), module);
        }
        List<String> planIds = modules.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();
        List<String> enabledSet = List.copyOf(planIds);

        RestoreProgress initial = new RestoreProgress(enabledSet);
        CompletableFuture<RestoreProgress> progress = CompletableFuture.completedFuture(initial);
        for (ModuleRuntime.RegisteredModule module : modules.planOrder()) {
            progress = progress.thenCompose(state -> restoreOne(player, module, stored, state));
        }
        // overall budget: when it fires, modules the chain has not reached
        // report TIMEOUT; finalize with what is actually done
        progress = progress.completeOnTimeout(initial, settings.restoreBudget().toMillis(), TimeUnit.MILLISECONDS);
        progress.whenComplete((state, err) -> {
            if (err != null) {
                LOGGER.error("restore pipeline blew up for {} (player may have left)", playerId, unwrap(err));
                finishFailed(playerId, "restore-pipeline", session);
                return;
            }
            finalizeRestore(player, fetched, state, enabledSet);
        });
    }

    private CompletableFuture<RestoreProgress> restoreOne(Player player,
                                                          ModuleRuntime.RegisteredModule module,
                                                          Map<String, ModuleSnapshot> stored,
                                                          RestoreProgress state) {
        java.util.UUID playerId = player.getUniqueId();
        if (state.aborted()) {
            state.rows().add(ModuleResult.skipped(module.id(), ModuleCodes.SKIPPED,
                    "not run: an earlier required module aborted the restore"));
            return CompletableFuture.completedFuture(state);
        }
        ModuleSnapshot storedModule = stored.get(module.id());
        if (storedModule == null) {
            state.rows().add(ModuleResult.skipped(module.id(), ModuleCodes.SKIPPED, "no payload in snapshot"));
            return CompletableFuture.completedFuture(state);
        }
        List<String> missingDependencies = module.module().dependencies().stream()
                .filter(dep -> !state.enabledSet().contains(dep))
                .toList();
        if (!missingDependencies.isEmpty()) {
            return failModule(playerId, module, ModuleCodes.DEPENDENCY_MISSING,
                    "required dependencies not enabled: " + String.join(", ", missingDependencies), 0, state);
        }
        long started = System.nanoTime();

        int storedVersion = storedModule.moduleVersion();
        int currentVersion = module.module().version();
        if (storedVersion > currentVersion) {
            return failModule(playerId, module, ModuleCodes.MIGRATION_FAILED,
                    "stored payload version " + storedVersion + " is newer than this build supports (" + currentVersion + ")",
                    millisSince(started), state);
        }
        if (storedVersion < currentVersion) {
            LOGGER.debug("module {} restoring from an older payload version {} (current {}); module owns the upgrade",
                    module.id(), storedVersion, currentVersion);
        }

        Object typed;
        try {
            typed = MAPPER.treeToValue(storedModule.payload(), module.payloadType());
        } catch (Throwable t) {
            LOGGER.warn("module {} payload did not deserialize to {}", module.id(), module.payloadType().getSimpleName(), t);
            return failModule(playerId, module, ModuleCodes.INVALID_DATA,
                    "payload did not match the module schema: " + detailOf(t), millisSince(started), state);
        }
        if (typed == null) {
            return failModule(playerId, module, ModuleCodes.INVALID_DATA, "payload decoded to null",
                    millisSince(started), state);
        }

        // validate, then restore — both bounded, both on the entity lane, never blocking it
        Object payload = typed;
        long moduleTimeoutMillis = module.timeoutMillis(settings.moduleIoTimeout().toMillis());
        java.util.concurrent.atomic.AtomicBoolean validateFailed = new java.util.concurrent.atomic.AtomicBoolean();
        return callOnEntityLane(player, () -> module.moduleAs().validate(player, payload), module)
                .orTimeout(moduleTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((validation, validateErr) -> {
                    Throwable error = unwrap(validateErr);
                    if (error != null) {
                        validateFailed.set(true);
                        return failModule(playerId, module,
                                timeoutAwareCode(error, ModuleCodes.VALIDATION_FAILED), detailOf(error),
                                millisSince(started), state);
                    }
                    if (validation == null || !validation.valid()) {
                        validateFailed.set(true);
                        String reason = validation == null ? "validate returned no result" : validation.reason();
                        return failModule(playerId, module, ModuleCodes.VALIDATION_FAILED, reason,
                                millisSince(started), state);
                    }
                    return null; // ok — proceed to restore
                })
                .thenCompose(validateFailure -> {
                    if (validateFailed.get()) {
                        // failModule already completed this future with the failure row recorded
                        return validateFailure;
                    }
                    return callOnEntityLane(player, () -> module.moduleAs().restore(player, payload), module)
                            .orTimeout(moduleTimeoutMillis, TimeUnit.MILLISECONDS)
                            .handle((restored, restoreErr) -> {
                                Throwable error = unwrap(restoreErr);
                                long millis = millisSince(started);
                                if (error != null) {
                                    return failModule(playerId, module,
                                            timeoutAwareCode(error, ModuleCodes.RESTORE_FAILED), detailOf(error),
                                            millis, state);
                                }
                                state.rows().add(ModuleResult.ok(module.id(), millis));
                                return CompletableFuture.completedFuture(state);
                            })
                            .thenCompose(finished -> finished);
                });
    }

    // ---------- report / settle ----------

    private CompletableFuture<RestoreProgress> failModule(java.util.UUID playerId,
                                                          ModuleRuntime.RegisteredModule module,
                                                          String code,
                                                          String detail,
                                                          long millis,
                                                          RestoreProgress state) {
        state.rows().add(ModuleResult.failed(module.id(), code, detail, millis));
        if (module.failurePolicy() == FailurePolicy.ABORT) {
            state.markAborted();
        }
        LOGGER.warn("module {} failed during restore for {} ({}){}",
                module.id(), playerId, code, state.aborted() ? "; aborting restore" : "");
        return CompletableFuture.completedFuture(state);
    }

    private void finalizeRestore(Player player, FetchOutcome fetched, RestoreProgress state, List<String> enabledSet) {
        java.util.UUID playerId = player.getUniqueId();
        List<ModuleResult> rows = new ArrayList<>(state.rows());
        // modules the budget left unaccounted for: TIMEOUT rows; a required one fails the restore
        Set<String> reported = new HashSet<>();
        for (ModuleResult row : rows) {
            reported.add(row.moduleId());
        }
        for (String enabledId : enabledSet) {
            if (reported.contains(enabledId)) {
                continue;
            }
            ModuleRuntime.RegisteredModule module = modules.byId(enabledId);
            boolean required = module != null && module.failurePolicy() == FailurePolicy.ABORT;
            rows.add(ModuleResult.failed(enabledId, ModuleCodes.TIMEOUT,
                    "restore budget expired before the module reported", 0));
            if (required) {
                state.markAborted();
            }
        }
        // modules stored but disabled on this backend are reported as skipped, after the plan rows
        for (ModuleSnapshot module : fetched.snapshot().envelope().modules()) {
            if (!enabledSet.contains(module.moduleId())) {
                rows.add(ModuleResult.skipped(module.moduleId(), ModuleCodes.DISABLED,
                        "module disabled on this backend"));
            }
        }

        boolean anyFailed = rows.stream().anyMatch(ModuleResult::failed);
        SyncResult verdict = state.aborted() ? SyncResult.FAILED : (anyFailed ? SyncResult.PARTIAL : SyncResult.SUCCESS);
        SessionRecord session = fetched.session();
        PlayerSnapshot snapshot = fetched.snapshot();

        for (ModuleResult row : rows) {
            events.dispatch(new ContinuumModuleRestoreEvent(playerId, row));
        }

        long snapshotId = snapshot.fencingToken();
        if (snapshotId < 1) {
            LOGGER.warn("loaded snapshot for {} carries fencing token {}; reporting 1 as the snapshot id", playerId, snapshotId);
            snapshotId = 1;
        }
        if (state.aborted()) {
            sessions.remove(playerId);
            lastKnownGood.remove(playerId);
            markAllEnabledDirty(playerId);
            transitionSessionQuietly(playerId, session, SessionState.RECOVERY_REQUIRED);
            LOGGER.warn("restore FAILED for {}; player kicked by the gate, session marked RECOVERY_REQUIRED", playerId);
            gate.markFailed(playerId, "restore-failed");
            fireRestoreEvent(playerId, SyncResult.FAILED, rows, snapshotId);
            return;
        }
        // applied state == last known good
        lastKnownGood.put(playerId, snapshot);
        dirty.clearAll(playerId);
        transitionSessionQuietly(playerId, session, SessionState.ACTIVE);
        gate.markReady(playerId);
        LOGGER.info("restore for {} complete ({})", playerId, verdict);
        fireRestoreEvent(playerId, verdict, rows, snapshotId);
    }

    private void fireRestoreEvent(java.util.UUID playerId, SyncResult verdict, List<ModuleResult> rows, long snapshotId) {
        try {
            RestoreReport report = new RestoreReport(playerId, snapshotId, verdict, rows, Instant.now());
            events.dispatch(new ContinuumRestoreEvent(playerId, report));
        } catch (RuntimeException e) {
            LOGGER.error("could not assemble/dispatch the restore report for {}", playerId, e);
        }
    }

    private void finishFailed(java.util.UUID playerId, String reason, SessionRecord session) {
        sessions.remove(playerId);
        lastKnownGood.remove(playerId);
        markAllEnabledDirty(playerId);
        if (session != null) {
            transitionSessionQuietly(playerId, session, SessionState.RECOVERY_REQUIRED);
        }
        try {
            gate.markFailed(playerId, reason);
        } catch (Throwable t) {
            LOGGER.error("gate refused the failure mark for {}", playerId, t);
        }
        events.dispatch(new ContinuumFailureEvent(playerId, null, "RESTORE_FAILED", "restore failed: " + reason));
    }

    // ---------- plumbing ----------

    private void markAllEnabledDirty(java.util.UUID playerId) {
        dirty.markAllDirty(playerId, modules.enabledIds());
    }

    private void transitionSessionQuietly(java.util.UUID playerId, SessionRecord session, SessionState state) {
        if (session == null) {
            return;
        }
        fifo.submit(playerId, () -> storage.transitionSession(playerId, session.token(), state))
                .whenComplete((record, err) -> {
                    if (err != null) {
                        LOGGER.warn("session transition to {} failed for {}", state, playerId, unwrap(err));
                    }
                });
    }

    private void hopToEntityLane(Player player, Runnable task) {
        java.util.UUID playerId = player.getUniqueId();
        player.getScheduler().execute(plugin, () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.error("restore hop failed for {}", playerId, t);
                finishFailed(playerId, "restore-hop", sessions.get(playerId));
            }
        }, () -> finishFailed(playerId, "player-left", sessions.get(playerId)), 0L);
    }

    /** Calls one module on the entity lane; the stage it returns is awaited off the lane. Never blocks the lane. */
    private <T> CompletableFuture<T> callOnEntityLane(Player player,
                                                      Supplier<? extends CompletionStage<T>> call,
                                                      ModuleRuntime.RegisteredModule module) {
        CompletableFuture<T> future = new CompletableFuture<>();
        player.getScheduler().execute(plugin, () -> {
            CompletionStage<T> stage;
            try {
                stage = call.get();
            } catch (Throwable t) {
                future.completeExceptionally(t);
                return;
            }
            if (stage == null) {
                future.completeExceptionally(new IllegalStateException("module " + module.id() + " returned a null stage"));
                return;
            }
            stage.whenComplete((value, err) -> {
                Throwable error = unwrap(err);
                if (error != null) {
                    future.completeExceptionally(error);
                } else {
                    future.complete(value);
                }
            });
        }, () -> future.completeExceptionally(
                new IllegalStateException("player left before the module could run")), 0L);
        return future;
    }

    private static long millisSince(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static Throwable unwrap(Throwable t) {
        if (t == null) {
            return null;
        }
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static String timeoutAwareCode(Throwable error, String fallbackCode) {
        return error instanceof TimeoutException ? ModuleCodes.TIMEOUT : fallbackCode;
    }

    private static String detailOf(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? error.toString() : message;
    }

    /** One snapshot row fetched for the restore; carries the session the read/write will be fenced with. */
    private record FetchOutcome(SessionRecord session, PlayerSnapshot snapshot) {
    }

    /** Mutable accumulator threaded through the per-module restore chain. */
    private static final class RestoreProgress {

        private final List<String> enabledSet;
        private final List<ModuleResult> rows = new ArrayList<>();
        private boolean aborted;

        private RestoreProgress(List<String> enabledSet) {
            this.enabledSet = enabledSet;
        }

        List<String> enabledSet() {
            return enabledSet;
        }

        List<ModuleResult> rows() {
            return rows;
        }

        boolean aborted() {
            return aborted;
        }

        void markAborted() {
            this.aborted = true;
        }
    }

    private static final class RestoreAbortException extends Exception {

        private RestoreAbortException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
