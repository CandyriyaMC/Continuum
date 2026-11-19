package lol.candyriya.continuum.api.report;

import lol.candyriya.continuum.api.module.ModuleIds;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The outcome of one module in one capture or restore operation — the fine-grained,
 * per-module row of a {@link SyncReport}.
 *
 * <p>Instances are immutable. The {@link #code} is a stable machine-readable token (see
 * {@link ModuleCodes}); {@link #detail} is an optional human-readable English explanation
 * for logs and diagnostics surfaces, never parsed by code.</p>
 *
 * @param moduleId the module this row is about, never null, well-formed module id
 * @param status the coarse outcome, never null
 * @param code the machine-readable reason, never null, never blank; typically one of
 *        {@link ModuleCodes}
 * @param detail an optional English explanation for humans and logs, nullable
 * @param durationMillis wall-clock time the module took, &ge; 0
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record ModuleResult(
        @NotNull String moduleId,
        @NotNull ModuleStatus status,
        @NotNull String code,
        @Nullable String detail,
        long durationMillis) {

    /**
     * Canonical constructor with validation.
     *
     * @param moduleId the module, never null; must satisfy {@code ModuleIds.requireValid}
     * @param status the coarse outcome, never null
     * @param code the machine-readable reason, never null, never blank
     * @param detail an optional English explanation, may be null
     * @param durationMillis wall-clock time the module took, &ge; 0
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when {@code moduleId} is not well-formed
     * @throws NullPointerException when {@code status} is null
     * @throws IllegalArgumentException when {@code code} is null/blank or {@code durationMillis} is negative
     */
    public ModuleResult {
        ModuleIds.requireValid(moduleId);
        if (status == null) {
            throw new NullPointerException("status must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be >= 0, got " + durationMillis);
        }
    }

    /**
     * Shorthand for a successful module.
     *
     * @param moduleId the module, never null, well-formed
     * @param durationMillis how long it took, &ge; 0
     * @return the result row
     */
    @NotNull
    public static ModuleResult ok(@NotNull String moduleId, long durationMillis) {
        return new ModuleResult(moduleId, ModuleStatus.OK, ModuleCodes.OK, null, durationMillis);
    }

    /**
     * Shorthand for a successful-but-noteworthy module.
     *
     * @param moduleId the module, never null, well-formed
     * @param code the warning code, never null, never blank
     * @param detail what to watch out for, in English; may be null
     * @param durationMillis how long it took, &ge; 0
     * @return the result row
     */
    @NotNull
    public static ModuleResult warn(@NotNull String moduleId, @NotNull String code,
                                    @Nullable String detail, long durationMillis) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        return new ModuleResult(moduleId, ModuleStatus.WARN, code, detail, durationMillis);
    }

    /**
     * Shorthand for a failed module.
     *
     * @param moduleId the module, never null, well-formed
     * @param code why it failed, never null, never blank; typically one of
     *        {@code CAPTURE_FAILED}/{@code RESTORE_FAILED}/{@code TIMEOUT}/...
     * @param detail the underlying failure or explanation, in English; may be null
     * @param durationMillis how long it took, &ge; 0
     * @return the result row
     */
    @NotNull
    public static ModuleResult failed(@NotNull String moduleId, @NotNull String code,
                                      @Nullable String detail, long durationMillis) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        return new ModuleResult(moduleId, ModuleStatus.FAILED, code, detail, durationMillis);
    }

    /**
     * Shorthand for a module that did not run.
     *
     * @param moduleId the module, never null, well-formed
     * @param code why it was skipped, never null, never blank; typically
     *        {@link ModuleCodes#SKIPPED}, {@link ModuleCodes#DEPENDENCY_MISSING} or
     *        {@link ModuleCodes#DISABLED}
     * @param detail the reason in English; may be null
     * @return the result row, with {@code durationMillis} of 0
     */
    @NotNull
    public static ModuleResult skipped(@NotNull String moduleId, @NotNull String code, @Nullable String detail) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
        return new ModuleResult(moduleId, ModuleStatus.SKIPPED, code, detail, 0);
    }

    /**
     * Whether this row represents a failed module.
     *
     * @return true when {@link #status()} is {@link ModuleStatus#FAILED}
     */
    public boolean failed() {
        return status == ModuleStatus.FAILED;
    }
}
