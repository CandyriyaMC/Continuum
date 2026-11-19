package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.module.ModuleIds;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired when something in Continuum failed in a way callers may care about — a capture or
 * restore that could not proceed, a storage operation that errored, a messaging problem.
 * Informational: there is nothing to cancel and no guaranteed follow-up.
 *
 * <p>{@link #playerId()} is null for global failures not tied to one player (a storage
 * outage, a coordinator problem). {@link #moduleId()} is null when the failure is not
 * attributable to a single module. {@link #code()} is a stable machine-readable token;
 * when the failure originates inside a sync operation it is one of
 * {@code lol.candyriya.continuum.api.report.ModuleCodes}, otherwise a Continuum-internal
 * code that tooling should treat as opaque.</p>
 *
 * <p><b>Thread:</b> fired on the thread that detected the failure — typically
 * Continuum's infrastructure executor or a platform scheduler thread. Treat it as an
 * arbitrary thread. <b>Cancellable:</b> no. <b>Mutability:</b> read-only.</p>
 *
 * @param playerId the affected player, or null for global failures
 * @param moduleId the affected module, or null when not attributable to one module
 * @param code the stable machine-readable failure code, never null, never blank
 * @param message an English explanation suitable for logs, never null, never blank
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumFailureEvent(
        @Nullable UUID playerId,
        @Nullable String moduleId,
        @NotNull String code,
        @NotNull String message) implements ContinuumEvent {

    /**
     * Canonical constructor with validation.
     *
     * @throws NullPointerException when {@code code} or {@code message} is null
     * @throws IllegalArgumentException when {@code code} or {@code message} is blank, or
     *         {@code moduleId} is not a well-formed module id
     */
    public ContinuumFailureEvent {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (moduleId != null) {
            ModuleIds.requireValid(moduleId);
        }
    }
}
