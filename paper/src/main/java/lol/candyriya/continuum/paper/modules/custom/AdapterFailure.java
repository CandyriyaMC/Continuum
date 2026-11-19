package lol.candyriya.continuum.paper.modules.custom;

import java.util.Objects;

/**
 * One adapter's recorded failure. Travels inside the custom module's payload (capture
 * side) and inside the exceptions this package throws (restore/capture fatal side), so
 * the failure is visible in diagnostics instead of vanishing into a log line.
 *
 * @param adapterId      the id of the adapter that failed, never null
 * @param adapterVersion the adapter's schema version at the time of the failure, &ge; 1
 * @param code           the failure code: one of {@code CAPTURE_FAILED},
 *                       {@code RESTORE_FAILED}, {@code VALIDATION_FAILED},
 *                       {@code MIGRATION_FAILED}, {@code DEPENDENCY_MISSING},
 *                       {@code TIMEOUT}, {@code PAYLOAD_TOO_LARGE}
 * @param reason         what happened, in English, never null
 * @since 1.0.0
 */
public record AdapterFailure(String adapterId, int adapterVersion, String code, String reason) {

    /** Code: the adapter's capture produced nothing usable. */
    public static final String CODE_CAPTURE_FAILED = "CAPTURE_FAILED";

    /** Code: the adapter's restore threw or failed. */
    public static final String CODE_RESTORE_FAILED = "RESTORE_FAILED";

    /** Code: a payload was rejected by validation (corrupt or oversized). */
    public static final String CODE_VALIDATION_FAILED = "VALIDATION_FAILED";

    /** Code: a stored payload could not be migrated to the adapter's schema. */
    public static final String CODE_MIGRATION_FAILED = "MIGRATION_FAILED";

    /** Code: the module an adapter depends on (or the adapter's own stored data) was unavailable. */
    public static final String CODE_DEPENDENCY_MISSING = "DEPENDENCY_MISSING";

    /** Code: the adapter exceeded its configured timeout. */
    public static final String CODE_TIMEOUT = "TIMEOUT";

    /** Code: the adapter's payload exceeded the configured per-adapter size cap. */
    public static final String CODE_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    public AdapterFailure {
        Objects.requireNonNull(adapterId, "adapterId must not be null");
        if (adapterVersion < 1) {
            throw new IllegalArgumentException("adapterVersion must be >= 1, got " + adapterVersion);
        }
        Objects.requireNonNull(code, "code must not be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
