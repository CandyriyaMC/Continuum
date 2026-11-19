package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Access to Continuum's module registry — the list of everything that participates in
 * synchronization, and the registration entry point for third-party adapters.
 *
 * <p>Obtained via {@code ContinuumProvider.get().modules()}; the instance is stable for
 * the lifetime of the platform plugin. All methods are safe to call from any thread and
 * never perform blocking I/O.</p>
 *
 * <p><b>Registration guarantees</b></p>
 * <ul>
 *   <li>The id must satisfy {@link ModuleIds}, and must not be in the reserved
 *       {@code continuum} namespace (first-party only). Violations throw
 *       {@link InvalidModuleIdException}.</li>
 *   <li>The id must be unique among currently registered modules — a duplicate throws
 *       {@link DuplicateModuleIdException} and the already-registered module keeps its
 *       place.</li>
 *   <li>The adapter contract is checked (schema version, timeout, dependencies) via
 *       {@link ModuleContracts#validate(SyncAdapter)}; inconsistencies throw
 *       {@link InvalidModuleDefinitionException}.</li>
 *   <li>Global dependency cycles — module A depends on B, B (transitively) depends on A —
 *       are rejected with {@link InvalidModuleDefinitionException} when the registration
 *       would create one.</li>
 *   <li>An optional adapter's failure never takes down a restore operation; see
 *       {@link SyncAdapter#required()} and {@link FailurePolicy}.</li>
 * </ul>
 *
 * <p>Register during plugin enable, before Continuum processes any player. Registering
 * later is legal but the module only participates from the next capture/restore plan onwards.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface ModuleService {

    /**
     * Registers a third-party adapter. The adapter must be stateless and thread-safe;
     * Continuum calls its contract methods from arbitrary threads.
     *
     * @param adapter the adapter to register, never null
     * @param <T> the adapter's captured data type
     * @return the registration handle, never null; unregister with it (or let the platform
     *         unregister everything on shutdown)
     * @throws NullPointerException when {@code adapter} is null
     * @throws InvalidModuleIdException when the id is malformed or in the reserved namespace
     * @throws DuplicateModuleIdException when the id is already registered
     * @throws InvalidModuleDefinitionException when the adapter's contract is inconsistent
     *         or the registration would create a dependency cycle
     */
    @NotNull
    <T> ModuleRegistration register(@NotNull SyncAdapter<?, T> adapter);

    /**
     * The currently registered modules, in restore planning order: dependencies first
     * (topological), then {@link ModuleInfo#priority()} ascending, ties broken by id.
     *
     * <p>The returned list is an immutable snapshot — later registrations are not
     * reflected in it.</p>
     *
     * @return the modules, never null, possibly empty
     */
    @NotNull
    List<ModuleInfo> modules();

    /**
     * Looks up a registered module by id.
     *
     * @param id the module id, never null
     * @return the module metadata, or empty when no module with this id is registered
     * @throws NullPointerException when {@code id} is null
     */
    @NotNull
    Optional<ModuleInfo> module(@NotNull String id);

    /**
     * Whether a module with this id is currently registered.
     *
     * @param id the module id, never null
     * @return true when registered
     * @throws NullPointerException when {@code id} is null
     */
    default boolean isRegistered(@NotNull String id) {
        return module(id).isPresent();
    }
}
