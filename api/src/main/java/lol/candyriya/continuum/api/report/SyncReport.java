package lol.candyriya.continuum.api.report;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import lol.candyriya.continuum.api.snapshot.SyncResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The shared shape of the two sync reports — {@link CaptureReport} and
 * {@link RestoreReport}. A capture report answers &ldquo;what did we persist?&rdquo;, a
 * restore report answers &ldquo;what did the player actually get?&rdquo;.
 *
 * <p>Reports are immutable snapshots of one operation. They carry no timing or player
 * object beyond {@link #playerId()} — they are safe to log, persist alongside telemetry
 * and hand to diagnostics.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public sealed interface SyncReport
        permits CaptureReport, RestoreReport {

    /**
     * The player the operation was about.
     *
     * @return the player UUID, never null
     */
    @NotNull
    UUID playerId();

    /**
     * The overall verdict, derived from {@link #modules()}.
     *
     * @return the verdict, never null
     */
    @NotNull
    SyncResult result();

    /**
     * Per-module rows, in restore planning order.
     *
     * @return the module results, never null; the list is unmodifiable
     */
    @NotNull
    List<ModuleResult> modules();

    /**
     * When the operation's unit of work completed (for a capture, when the snapshot was
     * handed off for persistence).
     *
     * @return the completion time, never null
     */
    @NotNull
    Instant completedAt();

    /**
     * Looks up the row for one module.
     *
     * @param moduleId the module id; must not be null (well-formedness is not required to
     *        read back — a typo simply yields empty)
     * @return the row, or empty when the module did not participate
     * @throws NullPointerException when {@code moduleId} is null
     */
    default @NotNull Optional<ModuleResult> module(@NotNull String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        for (ModuleResult moduleResult : modules()) {
            if (moduleResult.moduleId().equals(moduleId)) {
                return Optional.of(moduleResult);
            }
        }
        return Optional.empty();
    }

    /**
     * The rows that represent failed modules.
     *
     * @return the failed rows, never null, possibly empty
     */
    default @NotNull List<ModuleResult> failedModules() {
        return modules().stream()
                .filter(ModuleResult::failed)
                .toList();
    }

    /**
     * Whether every module succeeded (or only warned).
     *
     * @return true when {@link #result()} is {@code SUCCESS}
     */
    default boolean isCompleteSuccess() {
        return result() == SyncResult.SUCCESS;
    }
}
