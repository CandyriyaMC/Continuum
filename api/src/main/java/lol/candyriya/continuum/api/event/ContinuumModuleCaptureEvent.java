package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.report.ModuleResult;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired once per module after its capture completes — success, failure, or skip. The
 * outcome of the operation as a whole is summarized by
 * {@link lol.candyriya.continuum.api.report.CaptureReport}; this event is the
 * fine-grained per-module stream for metrics and alerting.
 *
 * <p><b>Thread:</b> fired when the module's capture stage completes. For modules whose
 * capture completes inline on the player's platform scheduler thread (Paper main thread,
 * Folia entity region thread) — the common case — the event fires on that thread; for
 * modules that complete their stage on another executor, it fires on that executor's
 * thread. Treat it as an arbitrary thread. <b>Cancellable:</b> no. <b>Mutability:</b>
 * read-only.</p>
 *
 * @param playerId the player that was captured, never null
 * @param result the module's outcome row, never null
 * @since 1.0.0
 */
@ApiStatus.Experimental
@ApiStatus.AvailableSince("1.0.0")
public record ContinuumModuleCaptureEvent(
        @NotNull UUID playerId,
        @NotNull ModuleResult result) implements ContinuumEvent {

    /** Canonical constructor with validation. */
    public ContinuumModuleCaptureEvent {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(result, "result");
    }
}
