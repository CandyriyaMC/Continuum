package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.SyncAdapter;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * First-party module {@code continuum.custom}: the runtime container for everything
 * arbitrary. Registered adapters ride inside this one module so the rest of the platform
 * pipeline never has to know they exist.
 *
 * <p><b>capture</b> (invoked on the player's platform thread): every registered adapter is
 * called with a <em>guarded</em> player and a completion deadline of
 * {@link SyncAdapter#timeout()}. Per adapter:</p>
 * <ul>
 *   <li>the captured value is encoded to opaque JSON on an infrastructure executor — never
 *       on the entity thread;</li>
 *   <li>a payload above {@link CustomAdapterLimits#perAdapterCapBytes()} is dropped and
 *       recorded as {@code PAYLOAD_TOO_LARGE}; beyond {@link CustomAdapterLimits#totalCapBytes()}
 *       the whole capture fails hard with {@link PayloadTooLargeException};</li>
 *   <li>a non-JSON-encodable value is recorded as {@code CAPTURE_FAILED};</li>
 *   <li>a stage that throws or never completes is recorded as {@code TIMEOUT} or
 *       {@code CAPTURE_FAILED}.</li>
 * </ul>
 * The outcome lands in exactly one payload, with an honest {@code failures} list for the
 * adapters that lost data. A required adapter's failure aborts the capture
 * ({@link CustomAdapterCaptureException}, no snapshot persisted); an optional adapter's
 * failure is isolated.
 *
 * <p><b>restore</b> (invoked on the player's platform thread): entries apply sequentially,
 * in the registry's planning order (dependencies first, then priority, ties by id). For
 * each entry: the adapter must be registered (else {@code DEPENDENCY_MISSING}, the failure
 * policy comes from the stored entry), the payload version is routed (equal → decode
 * through the codec, older → {@code migrate}, newer → {@code MIGRATION_FAILED}), then
 * {@code validate}, then {@code restore} — each under the adapter's timeout. A required
 * failure aborts the remaining entries and completes the stage exceptionally with
 * {@link CustomAdapterRestoreException}; an optional failure is recorded and the rest
 * still applies.</p>
 *
 * <p><b>Late writes are refused.</b> While an adapter's stage is detached past its
 * deadline, calls through the guarded player throw {@link AdapterCancelledException}.
 * Limits: references the adapter obtained <em>before</em> the deadline (an inventory
 * handle, a location) are not guarded, and an adapter that parks its own thread keeps
 * that thread. The platform contract (call on the player thread, return promptly)
 * remains the primary defense; the guard is the backstop.</p>
 *
 * <p>{@link SyncModule#dependencies()} reports the {@code continuum.*} dependencies that
 * currently registered adapters declare, limited to first-party modules the registry was
 * told about — so the platform can restore them before this module and adapters depending
 * on e.g. vitals see vitals applied first. It never declares adapter-to-adapter edges
 * (the platform cannot see those) and never reports an unknown id.</p>
 *
 * <p>Thread-safety: the module keeps no mutable state of its own beyond its infrastructure
 * executors; everything per-operation is local.</p>
 */
public final class CustomAdapterModule implements SyncModule<Player, CustomAdapterPayload> {

    /** The module id. The {@code continuum.*} namespace is reserved for first-party modules; this module is one. */
    public static final String ID = "continuum.custom";

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomAdapterModule.class);

    private final AdapterRegistry registry;
    private final AdapterPayloadCodec codec;
    private final CustomAdapterLimits limits;
    private final ExecutorService infrastructure;
    private final ScheduledExecutorService timer;
    private final boolean ownsExecutors;

    /** Registry plus shipping defaults: Gson codec, the default limits, owned infrastructure executors. */
    public CustomAdapterModule(@NotNull AdapterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.codec = new GsonAdapterPayloadCodec();
        this.limits = CustomAdapterLimits.DEFAULT;
        this.infrastructure = Executors.newVirtualThreadPerTaskExecutor();
        this.timer = newDaemonTimer();
        this.ownsExecutors = true;
    }

    /**
     * Full injector constructor; ownership of the given executors stays with the caller.
     *
     * @param registry       the backend-local adapter registry, never null
     * @param codec          the JSON engine for adapter payloads, never null
     * @param limits         size governance, never null
     * @param infrastructure executor for encode/decode work, never null, must not be shut down
     * @param timer          scheduled executor for completion deadlines, never null, must not be shut down
     */
    public CustomAdapterModule(@NotNull AdapterRegistry registry,
                               @NotNull AdapterPayloadCodec codec,
                               @NotNull CustomAdapterLimits limits,
                               @NotNull ExecutorService infrastructure,
                               @NotNull ScheduledExecutorService timer) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.infrastructure = Objects.requireNonNull(infrastructure, "infrastructure must not be null");
        this.timer = Objects.requireNonNull(timer, "timer must not be null");
        if (infrastructure.isShutdown()) {
            throw new IllegalArgumentException("infrastructure executor must not be shut down");
        }
        if (timer.isShutdown()) {
            throw new IllegalArgumentException("timer executor must not be shut down");
        }
        this.ownsExecutors = false;
    }

    /** The registry this module was built on. */
    @NotNull
    public AdapterRegistry registry() {
        return registry;
    }

    /** The size governance in effect. */
    @NotNull
    public CustomAdapterLimits limits() {
        return limits;
    }

    /** Shuts down the owned infrastructure executors; a no-op for injected ones. Never throws. */
    public void close() {
        if (!ownsExecutors) {
            return;
        }
        infrastructure.shutdownNow();
        timer.shutdownNow();
        try {
            if (!timer.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("custom adapter timer did not terminate within 2s of shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public @NotNull String id() {
        return ID;
    }

    @Override
    public int version() {
        return CustomAdapterPayload.FORMAT;
    }

    @Override
    public @NotNull List<String> dependencies() {
        // report the continuum.* dependencies that currently registered adapters declare,
        // limited to first-party ids the registry actually knows — never an adapter id,
        // never an unknown one. planning must never break the platform, so anything weird
        // degrades to no declarations with a warning.
        try {
            var known = registry.knownFirstPartyIds();
            if (known.isEmpty()) {
                return List.of();
            }
            var resolved = new TreeSet<String>();
            for (RegisteredAdapter target : registry.plannedAdapters()) {
                for (String dependency : target.moduleInfo().dependencies()) {
                    if (known.contains(dependency)) {
                        resolved.add(dependency);
                    }
                }
            }
            return List.copyOf(resolved);
        } catch (RuntimeException e) {
            LOGGER.warn("could not compute first-party dependencies for custom adapters; declaring none", e);
            return List.of();
        }
    }

    @Override
    public @NotNull CompletionStage<CustomAdapterPayload> capture(@NotNull Player player) {
        Objects.requireNonNull(player, "player must not be null");
        List<RegisteredAdapter> planned = registry.plannedAdapters();
        if (planned.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new CustomAdapterPayload(CustomAdapterPayload.FORMAT, Instant.now(), List.of(), List.of()));
        }
        var outcomes = new ArrayList<CompletableFuture<AdapterCallOutcome>>(planned.size());
        for (RegisteredAdapter target : planned) {
            outcomes.add(captureOne(player, target));
        }
        return CompletableFuture.allOf(outcomes.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> assembleCapture(outcomes));
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull CustomAdapterPayload data) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(data, "data must not be null");

        // oversized stored data is refused before a single adapter sees the player
        int total = totalBytes(data.adapters());
        if (total > limits.totalCapBytes()) {
            return CompletableFuture.failedFuture(new PayloadTooLargeException(
                    "custom adapter payload of " + total + " bytes exceeds the configured cap of "
                            + limits.totalCapBytes() + " bytes; refusing to restore",
                    total, limits.totalCapBytes()));
        }

        List<AdapterEntry> ordered = orderForRestore(data.adapters());
        var failures = new ArrayList<AdapterFailure>();
        var fatal = new AtomicBoolean();
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (AdapterEntry entry : ordered) {
            chain = chain.thenCompose(ignored -> applyEntry(player, entry, failures, fatal));
        }
        return chain
                .thenCompose(ignored -> fatal.get()
                        ? CompletableFuture.<Void>failedFuture(new CustomAdapterRestoreException(
                        "custom adapter restore aborted by a required adapter failure", failures))
                        : CompletableFuture.<Void>completedFuture(null))
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    if (cause instanceof CustomAdapterRestoreException restoreException) {
                        throw restoreException;
                    }
                    // belt and suspenders: applyEntry is built to never throw; if one ever
                    // does, fold it into the abort instead of leaking a bare exception
                    LOGGER.error("unexpected failure while composing custom adapter restore", cause);
                    failures.add(new AdapterFailure(ID, CustomAdapterPayload.FORMAT,
                            AdapterFailure.CODE_RESTORE_FAILED,
                            "unexpected platform-side error: " + describe(cause)));
                    throw new CustomAdapterRestoreException(
                            "custom adapter restore failed: " + cause.getMessage(), failures);
                });
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull CustomAdapterPayload data) {
        Objects.requireNonNull(player, "player must not be null");
        // the player is not needed for payload sanity; it stays in the signature per the contract
        var problems = new ArrayList<String>();
        if (data.format() != CustomAdapterPayload.FORMAT) {
            problems.add("unsupported format " + data.format() + " (expected " + CustomAdapterPayload.FORMAT + ")");
        }
        var seen = new java.util.HashSet<String>();
        int total = 0;
        for (AdapterEntry entry : data.adapters()) {
            if (!seen.add(entry.adapterId())) {
                problems.add("duplicate entry '" + entry.adapterId() + "'");
            }
            int size = utf8Length(entry.payload());
            if (size > limits.perAdapterCapBytes()) {
                problems.add("adapter '" + entry.adapterId() + "' payload of " + size
                        + " bytes exceeds the per-adapter cap of " + limits.perAdapterCapBytes() + " bytes");
            }
            total += size;
        }
        if (total > limits.totalCapBytes()) {
            problems.add("payload of " + total + " bytes exceeds the total cap of " + limits.totalCapBytes() + " bytes");
        }
        if (problems.isEmpty()) {
            return CompletableFuture.completedFuture(ValidationResult.ok());
        }
        return CompletableFuture.completedFuture(ValidationResult.invalid(String.join("; ", problems)));
    }

    // ---------- capture internals ----------

    private CompletableFuture<AdapterCallOutcome> captureOne(Player player, RegisteredAdapter target) {
        String id = target.id();
        FailurePolicy policy = target.adapter().failurePolicy();
        boolean fatal = policy == FailurePolicy.ABORT;
        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(player, cancelled);

        final CompletionStage<?> stage;
        try {
            @SuppressWarnings("unchecked")
            CompletionStage<?> captured = ((SyncAdapter<Player, ?>) target.adapter()).capture(guarded);
            stage = captured;
        } catch (Throwable t) {
            // a rogue adapter must not take the entity thread down with it
            LOGGER.error("adapter '{}' threw inside capture()", id, t);
            return CompletableFuture.completedFuture(AdapterCallOutcome.failure(
                    failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_CAPTURE_FAILED,
                            "capture threw " + describe(t)), fatal));
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(AdapterCallOutcome.failure(
                    failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_CAPTURE_FAILED,
                            "capture() returned null instead of a stage"), fatal));
        }

        // deadline composes the adapter's stage; verdicts and JSON encoding run off the entity thread
        return TimeoutCall.detach(stage, target.adapter().timeout(), timer, cancelled)
                .handleAsync((value, error) -> {
                    try {
                        if (error != null) {
                            Throwable cause = TimeoutCall.unwrap(error);
                            boolean timeout = TimeoutCall.isDeadlineTimeout(cause);
                            String code = timeout ? AdapterFailure.CODE_TIMEOUT : AdapterFailure.CODE_CAPTURE_FAILED;
                            LOGGER.warn("adapter '{}' failed capture ({})", id, code, cause);
                            return AdapterCallOutcome.failure(
                                    failureRecord(id, target.moduleInfo().version(), code,
                                            timeout
                                                    ? "capture timed out after " + target.adapter().timeout().toMillis() + " ms"
                                                    : "capture failed: " + describe(cause)),
                                    fatal);
                        }
                        return encodeOutcome(target, value);
                    } catch (Throwable t) {
                        // handle never propagates; the module's own flow would be the bug
                        LOGGER.error("adapter '{}' capture flow failed unexpectedly", id, t);
                        return AdapterCallOutcome.failure(
                                failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_CAPTURE_FAILED,
                                        "internal capture failure: " + describe(t)), fatal);
                    }
                }, infrastructure);
    }

    private AdapterCallOutcome encodeOutcome(RegisteredAdapter target, Object value) {
        String id = target.id();
        boolean fatal = target.adapter().failurePolicy() == FailurePolicy.ABORT;
        if (value == null) {
            return AdapterCallOutcome.failure(
                    failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_CAPTURE_FAILED,
                            "capture completed with a null payload"), fatal);
        }
        final String encoded;
        try {
            encoded = codec.encode(value, target.payloadType());
        } catch (Throwable t) {
            LOGGER.warn("adapter '{}' produced a non-JSON-encodable payload", id, t);
            return AdapterCallOutcome.failure(
                    failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_CAPTURE_FAILED,
                            "payload is not JSON-encodable: " + describe(t)), fatal);
        }
        int size = utf8Length(encoded);
        int cap = limits.perAdapterCapBytes();
        if (size > cap) {
            LOGGER.warn("adapter '{}' payload of {} bytes exceeds the {}-byte per-adapter cap; dropping the entry",
                    id, size, cap);
            return AdapterCallOutcome.failure(
                    failureRecord(id, target.moduleInfo().version(), AdapterFailure.CODE_PAYLOAD_TOO_LARGE,
                            "payload of " + size + " bytes exceeds the per-adapter cap of " + cap + " bytes"), fatal);
        }
        FailurePolicy policy = target.adapter().failurePolicy();
        AdapterEntry entry = new AdapterEntry(id, target.moduleInfo().version(), target.adapter().required(),
                policy, encoded, Instant.now());
        return AdapterCallOutcome.success(entry, size);
    }

    private CustomAdapterPayload assembleCapture(List<CompletableFuture<AdapterCallOutcome>> outcomes) {
        var entries = new ArrayList<AdapterEntry>();
        var failures = new ArrayList<AdapterFailure>();
        int total = 0;
        for (CompletableFuture<AdapterCallOutcome> future : outcomes) {
            AdapterCallOutcome outcome;
            try {
                outcome = future.join();
            } catch (CompletionException e) {
                // belt and suspenders: members are built to never complete exceptionally
                LOGGER.error("adapter outcome future failed unexpectedly", e);
                failures.add(new AdapterFailure(ID, CustomAdapterPayload.FORMAT,
                        AdapterFailure.CODE_CAPTURE_FAILED,
                        "internal flow failure: " + describe(e.getCause() != null ? e.getCause() : e)));
                continue;
            }
            if (outcome.entry() != null) {
                total += outcome.sizeBytes();
                entries.add(outcome.entry());
            } else {
                failures.add(outcome.failure());
                if (outcome.fatal()) {
                    throw new CustomAdapterCaptureException(
                            "required adapter '" + outcome.failure().adapterId() + "' failed: "
                                    + outcome.failure().reason(), failures);
                }
            }
        }
        if (total > limits.totalCapBytes()) {
            for (AdapterEntry entry : entries) {
                LOGGER.warn("custom payload contribution: adapter '{}' serialized to {} bytes",
                        entry.adapterId(), utf8Length(entry.payload()));
            }
            throw new PayloadTooLargeException(
                    "custom adapter payload of " + total + " bytes exceeds the configured cap of "
                            + limits.totalCapBytes() + " bytes; refusing to persist",
                    total, limits.totalCapBytes());
        }
        return new CustomAdapterPayload(CustomAdapterPayload.FORMAT, Instant.now(), entries, failures);
    }

    // ---------- restore internals ----------

    /** Entries in planning order; unregistered adapters keep their stored order at the tail. */
    private List<AdapterEntry> orderForRestore(List<AdapterEntry> entries) {
        List<RegisteredAdapter> planned = registry.plannedAdapters();
        var plannedIndex = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < planned.size(); i++) {
            plannedIndex.put(planned.get(i).id(), i);
        }
        var ordered = new ArrayList<>(entries);
        // stable sort: unregistered adapters keep their stored relative order at the tail
        ordered.sort(Comparator.comparingInt(
                entry -> plannedIndex.getOrDefault(entry.adapterId(), Integer.MAX_VALUE)));
        return List.copyOf(ordered);
    }

    private int totalBytes(List<AdapterEntry> entries) {
        int total = 0;
        for (AdapterEntry entry : entries) {
            total += utf8Length(entry.payload());
        }
        return total;
    }

    /**
     * Applies one entry. Never throws: a fatal verdict records into {@code failures} and
     * flips {@code fatal}; callers short-circuit on the following iterations.
     */
    private CompletionStage<Void> applyEntry(Player player,
                                             AdapterEntry entry,
                                             List<AdapterFailure> failures,
                                             AtomicBoolean fatal) {
        if (fatal.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String id = entry.adapterId();
        RegisteredAdapter found = registry.find(id).orElse(null);
        FailurePolicy policy = found != null ? found.adapter().failurePolicy() : entry.failurePolicy();
        boolean entryFatal = policy == FailurePolicy.ABORT;

        if (found == null) {
            LOGGER.warn("no adapter '{}' is registered at restore time; its stored data cannot be applied", id);
            return record(failures, fatal,
                    failureRecord(id, entry.adapterVersion(), AdapterFailure.CODE_DEPENDENCY_MISSING,
                            "no adapter registered for id '" + id + "'"), entryFatal);
        }

        int perEntry = utf8Length(entry.payload());
        if (perEntry > limits.perAdapterCapBytes()) {
            LOGGER.warn("adapter '{}' stored payload of {} bytes exceeds the {}-byte per-adapter cap; refusing to apply",
                    id, perEntry, limits.perAdapterCapBytes());
            return record(failures, fatal,
                    failureRecord(id, entry.adapterVersion(), AdapterFailure.CODE_VALIDATION_FAILED,
                            "stored payload of " + perEntry + " bytes exceeds the per-adapter cap of "
                                    + limits.perAdapterCapBytes() + " bytes"), entryFatal);
        }

        SyncAdapter<?, ?> adapter = found.adapter();
        int stored = entry.adapterVersion();
        int current = adapter.version();
        if (stored > current) {
            return record(failures, fatal,
                    failureRecord(id, stored, AdapterFailure.CODE_MIGRATION_FAILED,
                            "stored payload version " + stored + " is newer than the adapter's " + current
                                    + "; only older payloads can be migrated"), entryFatal);
        }

        var cancelled = new AtomicBoolean();
        Player guarded = AdapterCallGuard.wrap(player, cancelled);

        final boolean migration = stored < current;
        final CompletionStage<Object> dataStage;
        if (migration) {
            dataStage = migratedStage(found, entry);
        } else {
            dataStage = CompletableFuture.supplyAsync(
                    () -> decode(found, entry.payload()), infrastructure);
        }
        return TimeoutCall.detach(dataStage, adapter.timeout(), timer, cancelled)
                .thenCompose(data -> validateThenRestore(guarded, found, adapter, entry, data,
                        failures, fatal, cancelled, entryFatal))
                .exceptionally(error -> {
                    // failed before validate: timeout (both paths), migration failure, decode failure
                    Throwable cause = TimeoutCall.unwrap(error);
                    boolean timeout = TimeoutCall.isDeadlineTimeout(cause);
                    String code = timeout ? AdapterFailure.CODE_TIMEOUT
                            : migration ? AdapterFailure.CODE_MIGRATION_FAILED : AdapterFailure.CODE_VALIDATION_FAILED;
                    LOGGER.warn("adapter '{}' failed to prepare its payload ({})", id, code, cause);
                    fail(failures, fatal, failureRecord(id, stored, code,
                            timeout ? "timed out after " + adapter.timeout().toMillis() + " ms"
                                    : migration ? describe(cause)
                                    : "stored payload could not be decoded: " + describe(cause)), entryFatal);
                    return null;
                });
    }

    private CompletionStage<Object> migratedStage(RegisteredAdapter found, AdapterEntry entry) {
        try {
            @SuppressWarnings("unchecked")
            CompletionStage<Object> stage = ((SyncAdapter<Player, Object>) found.adapter())
                    .migrate(entry.adapterVersion(), entry.payload());
            return stage != null ? stage
                    : CompletableFuture.failedFuture(new NullPointerException(
                    "migrate() returned null instead of a stage"));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private CompletionStage<Void> validateThenRestore(Player guarded,
                                                      RegisteredAdapter found,
                                                      SyncAdapter<?, ?> adapter,
                                                      AdapterEntry entry,
                                                      Object data,
                                                      List<AdapterFailure> failures,
                                                      AtomicBoolean fatal,
                                                      AtomicBoolean cancelled,
                                                      boolean entryFatal) {
        String id = entry.adapterId();
        int stored = entry.adapterVersion();
        final CompletionStage<ValidationResult> validationStage;
        try {
            @SuppressWarnings("unchecked")
            CompletionStage<ValidationResult> stage =
                    ((SyncAdapter<Player, Object>) adapter).validate(guarded, data);
            validationStage = stage;
        } catch (Throwable t) {
            LOGGER.warn("adapter '{}' threw inside validate()", id, t);
            return record(failures, fatal, failureRecord(id, stored, AdapterFailure.CODE_VALIDATION_FAILED,
                    "validate threw: " + describe(t)), entryFatal);
        }
        if (validationStage == null) {
            return record(failures, fatal, failureRecord(id, stored, AdapterFailure.CODE_VALIDATION_FAILED,
                    "validate() returned null instead of a stage"), entryFatal);
        }
        return TimeoutCall.detach(validationStage, adapter.timeout(), timer, cancelled)
                .thenCompose(result -> {
                    if (result == null) {
                        return record(failures, fatal, failureRecord(id, stored,
                                AdapterFailure.CODE_VALIDATION_FAILED,
                                "validate() completed with null instead of a result"), entryFatal);
                    }
                    if (!result.valid()) {
                        LOGGER.warn("adapter '{}' rejected its payload: {}", id, result.reason());
                        return record(failures, fatal, failureRecord(id, stored,
                                AdapterFailure.CODE_VALIDATION_FAILED,
                                "validation rejected the payload: " + result.reason()), entryFatal);
                    }
                    final CompletionStage<Void> restoreStage;
                    try {
                        @SuppressWarnings("unchecked")
                        CompletionStage<Void> stage = ((SyncAdapter<Player, Object>) adapter).restore(guarded, data);
                        restoreStage = stage;
                    } catch (Throwable t) {
                        LOGGER.warn("adapter '{}' threw inside restore()", id, t);
                        return record(failures, fatal, failureRecord(id, stored,
                                AdapterFailure.CODE_RESTORE_FAILED, "restore threw: " + describe(t)), entryFatal);
                    }
                    if (restoreStage == null) {
                        return record(failures, fatal, failureRecord(id, stored,
                                AdapterFailure.CODE_RESTORE_FAILED,
                                "restore() returned null instead of a stage"), entryFatal);
                    }
                    return TimeoutCall.detach(restoreStage, adapter.timeout(), timer, cancelled)
                            .handle((ignored, error) -> {
                                if (error == null) {
                                    return null;
                                }
                                Throwable cause = TimeoutCall.unwrap(error);
                                boolean timeout = TimeoutCall.isDeadlineTimeout(cause);
                                String code = timeout ? AdapterFailure.CODE_TIMEOUT : AdapterFailure.CODE_RESTORE_FAILED;
                                LOGGER.warn("adapter '{}' failed restore ({})", id, code, cause);
                                fail(failures, fatal, failureRecord(id, stored, code,
                                        timeout ? "restore timed out after " + adapter.timeout().toMillis() + " ms"
                                                : "restore failed: " + describe(cause)), entryFatal);
                                return null;
                            });
                })
                .exceptionally(error -> {
                    Throwable cause = TimeoutCall.unwrap(error);
                    boolean timeout = TimeoutCall.isDeadlineTimeout(cause);
                    LOGGER.warn("adapter '{}' failed to validate ({})", id, timeout ? "timed out" : "error", cause);
                    fail(failures, fatal, failureRecord(id, stored,
                            timeout ? AdapterFailure.CODE_TIMEOUT : AdapterFailure.CODE_VALIDATION_FAILED,
                            timeout ? "validation timed out after " + adapter.timeout().toMillis() + " ms"
                                    : "validation failed: " + describe(cause)), entryFatal);
                    return null;
                });
    }

    /** Records a failure and marks the operation fatal when the policy is ABORT. */
    private static CompletionStage<Void> record(List<AdapterFailure> failures,
                                                AtomicBoolean fatal,
                                                AdapterFailure failure,
                                                boolean entryFatal) {
        fail(failures, fatal, failure, entryFatal);
        return CompletableFuture.completedFuture(null);
    }

    private static void fail(List<AdapterFailure> failures,
                             AtomicBoolean fatal,
                             AdapterFailure failure,
                             boolean entryFatal) {
        failures.add(failure);
        if (entryFatal) {
            fatal.set(true);
            LOGGER.error("required adapter '{}' failed ({}): {}", failure.adapterId(), failure.code(), failure.reason());
        } else {
            LOGGER.warn("optional adapter '{}' failed ({}): {} — continuing", failure.adapterId(), failure.code(), failure.reason());
        }
    }

    private Object decode(RegisteredAdapter found, String payload) {
        return codec.decode(payload, found.payloadType());
    }

    private static AdapterFailure failureRecord(String adapterId, int adapterVersion, String code, String reason) {
        return new AdapterFailure(adapterId, Math.max(1, adapterVersion), code,
                reason == null || reason.isBlank() ? "unknown failure" : reason);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static ScheduledExecutorService newDaemonTimer() {
        var executor = new ScheduledThreadPoolExecutor(2, new DaemonThreadFactory("continuum-custom-timer"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger counter = new AtomicInteger();

        private DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
