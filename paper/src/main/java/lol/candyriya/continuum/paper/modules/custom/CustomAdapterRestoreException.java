package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.ModuleException;

import java.io.Serial;
import java.util.List;

/**
 * A required adapter failed during restore. Restore completes exceptionally with this
 * exception instead of half-applying the snapshot — the platform is expected to route the
 * player into the recovery flow, exactly as it does for first-party module failures.
 *
 * @since 1.0.0
 */
public final class CustomAdapterRestoreException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The restore-side failures recorded before the abort, in application order. Never null; lost on Java serialization (diagnostics only). */
    private final transient List<AdapterFailure> failures;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in English, never null
     * @param failures the adapter failures recorded up to the abort, in application order,
     *                 never null, never empty on this path
     */
    public CustomAdapterRestoreException(String message, List<AdapterFailure> failures) {
        super(message);
        this.failures = List.copyOf(failures);
    }

    /** The restore-side failures recorded up to the abort, in application order. Never null. */
    public List<AdapterFailure> failures() {
        return failures;
    }
}
