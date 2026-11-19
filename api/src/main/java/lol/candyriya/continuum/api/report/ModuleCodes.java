package lol.candyriya.continuum.api.report;

import org.jetbrains.annotations.ApiStatus;

/**
 * Machine-readable codes attached to {@link ModuleResult#code()}. The set is stable and
 * safe to switch on in third-party code; Continuum will only add new codes, never
 * reinterpret existing ones. Third-party adapters may also emit their own codes — treat
 * any unrecognized code as module-specific information, not as an error.
 *
 * <p>Codes describe <em>why</em> a module ended in its {@link ModuleStatus}; the status
 * alone never carries that detail.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class ModuleCodes {

    /** The module completed normally. Pairs with {@link ModuleStatus#OK}. */
    public static final String OK = "OK";

    /** The module completed with a non-fatal caveat. Pairs with {@link ModuleStatus#WARN}. */
    public static final String WARNING = "WARNING";

    /** The module was deliberately not run (disabled, or player not online for a live op). */
    public static final String SKIPPED = "SKIPPED";

    /** The module exceeded its configured timeout. */
    public static final String TIMEOUT = "TIMEOUT";

    /** The module threw or completed exceptionally while capturing. */
    public static final String CAPTURE_FAILED = "CAPTURE_FAILED";

    /** The module threw or completed exceptionally while restoring. */
    public static final String RESTORE_FAILED = "RESTORE_FAILED";

    /** The payload failed the module's validation gate. */
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** The stored payload could not be migrated to the module's schema version. */
    public static final String MIGRATION_FAILED = "MIGRATION_FAILED";

    /** A module this module depends on is absent from the registry. */
    public static final String DEPENDENCY_MISSING = "DEPENDENCY_MISSING";

    /** The payload deserialized structurally but was semantically unusable. */
    public static final String INVALID_DATA = "INVALID_DATA";

    /** The module is disabled by configuration on this backend. */
    public static final String DISABLED = "DISABLED";

    private ModuleCodes() {
        // constants only
    }
}
