package lol.candyriya.continuum.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Static access point to the running {@link ContinuumApi}.
 *
 * <p>Continuum is distributed as a plugin that is enabled by the platform (a Paper/Folia
 * backend or a Velocity proxy). Third-party plugins cannot receive the API through a
 * constructor, so they reach it through this class:</p>
 *
 * <pre>{@code
 * try {
 *     ContinuumApi api = ContinuumProvider.get();
 * } catch (ContinuumUnavailableException e) {
 *     // Continuum not installed or not enabled: disable the feature, do not retry in a loop
 * }
 * }</pre>
 *
 * <p><b>Registration contract (for the platform plugin).</b> The platform registers its
 * implementation exactly once per JVM lifetime with {@link #register(ContinuumApi)} at the
 * end of plugin enable, and unregisters it with {@link #unregister(ContinuumApi)} during
 * plugin disable. Both calls are deliberately guarded against misuse:</p>
 * <ul>
 *   <li>{@link #register} is idempotent for the <em>same</em> instance (safe to call again
 *       from a plugin reload) but refuses to silently replace a different implementation.
 *       Registering a second, distinct implementation throws {@link IllegalStateException}
 *       instead of corrupting the view third-party plugins hold.</li>
 *   <li>{@link #unregister} only clears the registration when the passed instance is the
 *       registered one. A platform instance that shuts down after a newer one has taken
 *       over cannot wipe the new registration out from under it.</li>
 * </ul>
 *
 * <p><b>Threading.</b> {@link #get()} and {@link #tryGet()} are cheap and may be called from
 * any thread. Registration methods synchronize internally and are safe on any thread.</p>
 *
 * <p><b>Nullability.</b> Never returns null; absence of a registration is reported via
 * {@link Optional} or by throwing from {@link #get()}.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class ContinuumProvider {

    private static volatile ContinuumApi instance;

    private ContinuumProvider() {
        // static access only
    }

    /**
     * Returns the registered {@link ContinuumApi}.
     *
     * @return the registered API, never null
     * @throws ContinuumUnavailableException if no implementation is currently registered
     *         (Continuum is disabled, not installed, or not yet enabled)
     */
    @NotNull
    public static ContinuumApi get() {
        ContinuumApi api = instance;
        if (api == null) {
            throw new ContinuumUnavailableException();
        }
        return api;
    }

    /**
     * Returns the registered {@link ContinuumApi}, or an empty optional when Continuum is
     * not running. Prefer this over {@link #get()} when the caller wants to probe for
     * Continuum without an exception.
     *
     * @return the registered API, or empty — never null
     */
    @NotNull
    public static Optional<ContinuumApi> tryGet() {
        return Optional.ofNullable(instance);
    }

    /**
     * Whether a Continuum implementation is currently registered.
     *
     * @return true while the platform plugin is enabled and has not shut down yet
     */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * Registers the platform's implementation. Called by the platform plugin at the end
     * of enable. Idempotent for the same instance; refuses to replace a different one.
     *
     * @param api the implementation, never null
     * @throws NullPointerException if {@code api} is null
     * @throws IllegalStateException if a <em>different</em> implementation is already registered
     */
    public static void register(@NotNull ContinuumApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (ContinuumProvider.class) {
            ContinuumApi current = instance;
            if (current == api) {
                // same instance re-registering (e.g. plugin reload) — nothing to do
                return;
            }
            if (current != null) {
                throw new IllegalStateException(
                        "Continuum is already provided by another implementation; "
                                + "refusing to silently replace it");
            }
            instance = api;
        }
    }

    /**
     * Unregisters the implementation, but only when {@code api} is the currently
     * registered instance. A stale platform shutting down after a newer implementation
     * registered is a no-op — it cannot clobber the newer registration.
     *
     * @param api the implementation to unregister, never null
     * @throws NullPointerException if {@code api} is null
     */
    public static void unregister(@NotNull ContinuumApi api) {
        Objects.requireNonNull(api, "api");
        synchronized (ContinuumProvider.class) {
            if (instance == api) {
                instance = null;
            }
        }
    }
}
