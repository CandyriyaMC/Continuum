package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * A handle to a module registration. Returned by
 * {@code ContinuumProvider.get().modules().register(adapter)}.
 *
 * <p>The handle lets the owning plugin unregister exactly its own module without
 * enumerating others, and without keeping a reference to Continuum's internals. It also
 * makes lifecycle cleanup symmetric with registration: close the handle in your
 * {@code onDisable} and the module is gone.</p>
 *
 * <p>{@link #unregister()} and {@link #close()} are idempotent: calling them twice is a
 * no-op the second time. Both are safe to call from any thread; the unregistration is
 * applied atomically with respect to concurrent sync operations — an in-flight operation
 * completes with the module as registered when it started, later operations see the
 * module gone. If the player data captured by this module is no longer desired, delete or
 * migrate it separately; unregistering never touches stored data.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public interface ModuleRegistration extends AutoCloseable {

    /**
     * The id of the registered module.
     *
     * @return the module id, never null
     */
    @NotNull
    String id();

    /**
     * Removes the module from the registry. Idempotent.
     *
     * <p>After unregistration the module no longer participates in captures, restores or
     * planning. Its stored snapshot payloads are left untouched.</p>
     */
    void unregister();

    /**
     * Whether the module is still registered.
     *
     * @return true until {@link #unregister()} (or {@link #close()}) completes
     */
    boolean isActive();

    /**
     * Equivalent to {@link #unregister()} — so the handle can be used in
     * try-with-resources. Never throws.
     */
    @Override
    default void close() {
        unregister();
    }
}
