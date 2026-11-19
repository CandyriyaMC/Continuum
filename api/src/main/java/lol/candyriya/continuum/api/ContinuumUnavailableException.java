package lol.candyriya.continuum.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;

/**
 * Thrown by {@link ContinuumProvider#get()} when no Continuum implementation is
 * registered — for example when the plugin is disabled, not yet enabled, or not
 * installed at all.
 *
 * <p>The exception is unchecked on purpose: most call sites cannot do anything useful
 * with a checked failure beyond aborting their own operation. Catch it when a third-party
 * plugin wants to degrade gracefully and hide Continuum-dependent features; otherwise let
 * it propagate and fail fast during startup, before any state can be diverged.</p>
 *
 * <p>Thread-safety: immutable, safe to share across threads.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class ContinuumUnavailableException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The default message, used by the no-arg-style construction. */
    static final String DEFAULT_MESSAGE = "Continuum is not available: the plugin is not running on this platform";

    /** Creates the exception with the default message. */
    public ContinuumUnavailableException() {
        super(DEFAULT_MESSAGE);
    }

    /**
     * Creates the exception with an explicit message.
     *
     * @param message what the caller should know, never null
     */
    public ContinuumUnavailableException(@NotNull String message) {
        super(message);
    }
}
