package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The core contract of a synchronization module.
 *
 * <p>A module owns one kind of player state — inventory, vitals, experience, and so on.
 * Continuum drives every module through the same lifecycle: <em>capture</em> the state
 * into the stored snapshot on the way out, <em>validate</em> and <em>restore</em> it on
 * the way in. First-party modules implement this interface directly; third-party plugins
 * use {@link SyncAdapter}, which extends this contract with registration knobs and a
 * migration hook.</p>
 *
 * <p><b>The player handle {@code P}.</b> Continuum is platform-neutral, so this interface
 * is generic over the player type. On Paper/Folia, {@code P} is {@code org.bukkit.entity.Player};
 * on any other platform it is whatever that platform uses for a live player. The API
 * module never sees {@code P}'s concrete type — it only passes the handle through.</p>
 *
 * <p><b>Threading contract — read this before implementing.</b></p>
 * <ul>
 *   <li>{@link #capture(Object)}, {@link #restore(Object, Object)} and
 *       {@link #validate(Object, Object)} are invoked by Continuum on the thread that owns
 *       the player, not on Continuum's infrastructure executors: on Paper, the server
 *       main thread; on Folia, the entity region thread of the player. Any Bukkit/Folia
 *       API call inside them is legal. On a worker or infrastructure thread it is not and
 *       will throw.</li>
 *   <li>Do <em>not</em> hop threads inside these methods to do heavy lifting. Read or
 *       mutate the live game state synchronously and complete the returned stage as soon
 *       as you hold the data (or are done mutating). Serialization to JSON, compression
 *       and persistence are Continuum's job and happen elsewhere.</li>
 *   <li>The returned stages may complete before, during or after the method returns.
 *       Continuum waits for the stage (subject to a per-module timeout) and never touches
 *       the player concurrently with your restore.</li>
 *   <li>{@link #id()}, {@link #version()}, {@link #priority()} and
 *       {@link #dependencies()} are called during restore planning from arbitrary threads.
 *       They must be cheap, stable and thread-safe — return constants or immutable
 *       snapshots, never do I/O or touch live state from them.</li>
 * </ul>
 *
 * <p><b>Data payload {@code T}.</b> {@code T} is whatever this module captured — a record,
 * a list, a nested structure of JDK types. Continuum serializes it as JSON into the
 * snapshot with the module's {@link #version()} as the schema marker; it never interprets
 * {@code T}. When you bump the schema, bump {@link #version()}. Payloads stored under an
 * older schema are migrated by {@link SyncAdapter#migrate(int, String)} when the module
 * is an adapter; modules that are first-party implementations of this interface own their
 * migration logic inside their own code.</p>
 *
 * <p><b>Module ids</b> follow the dotted lowercase convention enforced by
 * {@link ModuleIds}; {@code continuum.*} is reserved for Continuum's first-party modules.
 * The id is part of the persisted schema — never rename it in place.</p>
 *
 * @param <P> the platform player handle type (e.g. {@code org.bukkit.entity.Player})
 * @param <T> the module's captured data type
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface SyncModule<P, T> {

    /** The default restore priority: modules run in ascending priority order. */
    int DEFAULT_PRIORITY = 100;

    /**
     * The module id: a dotted lowercase identifier such as {@code continuum.inventory}.
     * Must satisfy {@link ModuleIds#requireValid(String)}; {@code continuum.*} is reserved.
     * Stable and unique across all registered modules.
     *
     * @return the module id, never null
     */
    @NotNull
    String id();

    /**
     * The data schema version of this module, starting at 1.
     *
     * <p>Bump it whenever the serialized form of {@code T} changes incompatibly — adding
     * an optional field does not require a bump, reinterpreting an existing field does.
     * Continuum stores this version next to every captured payload and compares it at
     * restore time to decide whether a migration is needed.</p>
     *
     * @return the schema version, always &ge; 1
     */
    int version();

    /**
     * Captures the player's state. Invoked on the player's platform scheduler thread.
     *
     * <p>The returned stage must complete with the captured payload or complete
     * exceptionally. Exceptional completion is recorded as a module failure with code
     * {@code CAPTURE_FAILED}; it does not, by itself, abort the capture of other modules.</p>
     *
     * @param player the live player, never null
     * @return the captured payload, or a stage completed exceptionally on failure
     */
    @NotNull
    CompletionStage<T> capture(@NotNull P player);

    /**
     * Applies a previously captured payload to the live player. Invoked on the
     * player's platform scheduler thread. Implementations should restore idempotently:
     * applying the same payload twice must not corrupt state.
     *
     * @param player the live player, never null
     * @param data the payload previously captured by this module, never null
     * @return completion of the restore, or a stage completed exceptionally on failure
     */
    @NotNull
    CompletionStage<Void> restore(@NotNull P player, @NotNull T data);

    /**
     * Optional sanity check of a payload before {@link #restore} applies it. The default
     * implementation accepts everything.
     *
     * <p>Implement this when a partially written or otherwise corrupted payload could
     * damage live state — e.g. an inventory with items that do not fit its bounds. An
     * invalid result is reported as {@code VALIDATION_FAILED}; the module is then skipped
     * (optional) or the restore is aborted (required), per its {@link FailurePolicy}.</p>
     *
     * @param player the live player, never null
     * @param data the payload handed to the module for restore, never null
     * @return the validation outcome; never null
     */
    @NotNull
    default CompletionStage<ValidationResult> validate(@NotNull P player, @NotNull T data) {
        return CompletableFuture.completedFuture(ValidationResult.ok());
    }

    /**
     * Restore planning hint: modules are restored in ascending priority order (ties broken
     * by module id for determinism), after their dependencies have been restored.
     *
     * <p>Lower values run earlier. First-party modules use built-in values; third-party
     * adapters should stay near the default and express ordering through
     * {@link #dependencies()} where possible.</p>
     *
     * @return the priority; any int, though values around {@link #DEFAULT_PRIORITY} are expected
     */
    default int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * Module ids this module depends on — for example {@code continuum.attributes} for the
     * vitals module, which must raise max health before health itself is written.
     *
     * <p>Dependencies are restored before their dependents regardless of
     * {@link #priority()}. A dependency that is not registered at restore time causes
     * this module to be reported as {@code DEPENDENCY_MISSING} (isolated when the module
     * is optional, aborting when required). Self-dependencies and duplicate entries are
     * rejected at registration time; global cycles are rejected when the restore plan is
     * built.</p>
     *
     * <p>Returned ids must be well-formed ({@link ModuleIds#requireValid(String)}).</p>
     *
     * @return the dependency ids, never null; elements never null; duplicates rejected at registration
     */
    @NotNull
    default List<String> dependencies() {
        return List.of();
    }
}
