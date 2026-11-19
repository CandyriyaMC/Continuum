package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of a registered module's metadata — what {@code ContinuumProvider.get().modules()}
 * reports about a module without triggering any capture or restore.
 *
 * <p>Built from the module's own contract at registration time; safe to hold, share and
 * compare. The list of registered {@code ModuleInfo} is also the basis of the restore
 * plan: Continuum topologically sorts dependencies (a module's dependencies run before
 * it) and then orders by {@link #priority()} ascending, breaking ties by id for
 * determinism.</p>
 *
 * <p>Instances are created at registration time, either through the canonical constructor
 * or {@link #of(SyncAdapter, String)}.</p>
 *
 * @param id the module id, dotted lowercase, e.g. {@code continuum.inventory}; never null, well-formed
 * @param version the data schema version of the module, &ge; 1
 * @param required whether the module's data is load-bearing (required) or isolatable (optional)
 * @param priority restore planning hint; ascending, ties broken by id
 * @param dependencies ids of modules restored before this one; unmodifiable, never null, elements never null
 * @param source where the module came from — {@code "continuum"} for first-party modules,
 *        otherwise the owning plugin's name; never null, never blank
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record ModuleInfo(
        @NotNull String id,
        int version,
        boolean required,
        int priority,
        @NotNull List<String> dependencies,
        @NotNull String source) {

    /**
     * Canonical constructor with validation and normalization.
     *
     * @throws InvalidModuleIdException when {@code id} is not well-formed
     * @throws InvalidModuleDefinitionException when {@code version} is below 1 or a
     *         dependency is malformed, self-referential or duplicated
     * @throws NullPointerException when {@code dependencies} or {@code source} is null
     * @throws IllegalArgumentException when {@code source} is blank
     */
    public ModuleInfo {
        ModuleIds.requireValid(id);
        if (version < 1) {
            throw new InvalidModuleDefinitionException(
                    "module '" + id + "': schema version must be >= 1, got " + version);
        }
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        // copy defensively and normalize; same rules as adapter registration
        var copied = new ArrayList<String>(dependencies.size());
        var seen = new HashSet<String>(dependencies.size());
        for (String dependency : dependencies) {
            if (dependency == null) {
                throw new InvalidModuleDefinitionException(
                        "module '" + id + "': dependencies must not contain null elements");
            }
            ModuleIds.requireValid(dependency);
            if (dependency.equals(id)) {
                throw new InvalidModuleDefinitionException(
                        "module '" + id + "': a module cannot depend on itself");
            }
            if (!seen.add(dependency)) {
                throw new InvalidModuleDefinitionException(
                        "module '" + id + "': duplicate dependency '" + dependency + "'");
            }
            copied.add(dependency);
        }
        dependencies = List.copyOf(copied);
    }

    /**
     * Builds a {@code ModuleInfo} from a registered adapter, validating the adapter's
     * contract first. Uniqueness of the id is not checked here — that is a registry
     * concern.
     *
     * @param adapter the adapter to describe, never null
     * @param source where the adapter comes from, e.g. the plugin name; never null, never blank
     * @return the module metadata, never null
     * @throws NullPointerException when {@code adapter} or {@code source} is null
     * @throws IllegalArgumentException when {@code source} is blank
     * @throws InvalidModuleIdException when the adapter's id is malformed or reserved
     * @throws InvalidModuleDefinitionException when any other contract detail is inconsistent
     */
    @NotNull
    public static ModuleInfo of(@NotNull SyncAdapter<?, ?> adapter, @NotNull String source) {
        ModuleContracts.validate(adapter);
        return new ModuleInfo(
                adapter.id(),
                adapter.version(),
                adapter.required(),
                adapter.priority(),
                ModuleContracts.dependenciesOf(adapter),
                source);
    }
}
