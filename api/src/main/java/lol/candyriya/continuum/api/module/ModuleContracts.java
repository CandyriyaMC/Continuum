package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

/**
 * Registration-time contract checks for adapters, shared by Continuum's implementation
 * and usable in third-party tests.
 *
 * <p>{@link ModuleService#register(SyncAdapter)} applies these checks before accepting an
 * adapter. They are exposed here so adapter authors can run the same validation in their
 * own unit tests and fail their build early — the same failure a server would log at
 * registration time, without starting a server.</p>
 *
 * <p>Thread-safety: stateless utility, safe to call from any thread.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class ModuleContracts {

    private ModuleContracts() {
        // static access only
    }

    /**
     * Validates an adapter's contract: id format, reserved namespace, schema version,
     * timeout and dependencies. Does <em>not</em> check id uniqueness — that is a registry
     * concern, enforced by {@link ModuleService#register(SyncAdapter)} itself.
     *
     * @param adapter the adapter to validate, never null
     * @throws NullPointerException when {@code adapter} is null
     * @throws InvalidModuleIdException when the id is malformed or in the reserved
     *         {@code continuum} namespace
     * @throws InvalidModuleDefinitionException when the schema version is below 1, the
     *         timeout is missing/non-positive, or the dependency list is inconsistent
     */
    public static void validate(@NotNull SyncAdapter<?, ?> adapter) {
        if (adapter == null) {
            throw new NullPointerException("adapter must not be null");
        }

        String id = adapter.id();
        ModuleIds.requireValid(id);
        if (ModuleIds.isReserved(id)) {
            throw new InvalidModuleIdException(id,
                    "the '" + ModuleIds.RESERVED_NAMESPACE + "' namespace is reserved for Continuum's first-party modules");
        }

        int version = adapter.version();
        if (version < 1) {
            throw new InvalidModuleDefinitionException(
                    "module '" + id + "': schema version must be >= 1, got " + version);
        }

        Duration timeout = adapter.timeout();
        if (timeout == null) {
            throw new InvalidModuleDefinitionException("module '" + id + "': timeout() must not be null");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new InvalidModuleDefinitionException(
                    "module '" + id + "': timeout() must be positive, got " + timeout);
        }

        List<String> dependencies = adapter.dependencies();
        if (dependencies == null) {
            throw new InvalidModuleDefinitionException(
                    "module '" + id + "': dependencies() must not be null (return List.of())");
        }
        // one pass: well-formed, no self-reference, no duplicates
        var seen = new HashSet<String>(dependencies.size());
        for (String dependency : dependencies) {
            if (dependency == null) {
                throw new InvalidModuleDefinitionException(
                        "module '" + id + "': dependencies() must not contain null elements");
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
        }
    }

    /**
     * Validates an adapter and returns an unmodifiable copy of its dependency list,
     * original order preserved. Registration implementations use this instead of
     * re-validating by hand.
     *
     * @param adapter the adapter to validate, never null
     * @return an unmodifiable copy of the adapter's dependencies, never null
     * @throws InvalidModuleIdException when the id is malformed or reserved
     * @throws InvalidModuleDefinitionException when any other contract detail is inconsistent
     */
    @NotNull
    public static List<String> dependenciesOf(@NotNull SyncAdapter<?, ?> adapter) {
        validate(adapter);
        return List.copyOf(adapter.dependencies());
    }
}
