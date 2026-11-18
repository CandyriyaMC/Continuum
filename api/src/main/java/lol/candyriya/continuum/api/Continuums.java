package lol.candyriya.continuum.api;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Entry point to the {@link ContinuumApi} for third-party code.
 *
 * <p>A plain service locator with a volatile field: the platform module sets the
 * implementation on startup and clears it on shutdown. Volatile is enough here —
 * registration happens once per plugin lifetime.</p>
 */
public final class Continuums {

    private static volatile ContinuumApi instance;

    private Continuums() {
    }

    /**
     * Registers the API implementation. Re-registering replaces the previous one —
     * a plugin reload without a server restart does not leave stale references behind.
     */
    public static void register(@NotNull ContinuumApi api) {
        instance = Objects.requireNonNull(api, "api");
    }

    /** Clears the implementation; call from onDisable / ProxyShutdownEvent. */
    public static void unregister() {
        instance = null;
    }

    /** Current implementation, empty when the plugin is not running. */
    public static @NotNull Optional<ContinuumApi> api() {
        return Optional.ofNullable(instance);
    }
}
