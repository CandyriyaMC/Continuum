package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.ModuleException;

import java.io.Serial;
import java.util.List;

/**
 * A required adapter could not capture: either the adapter itself failed (and its failure
 * policy is {@code ABORT}) or the module payload exceeds the total cap. Capture completes
 * exceptionally with this exception, so the snapshot is never persisted half-built.
 *
 * @since 1.0.0
 */
public final class CustomAdapterCaptureException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The adapter failures recorded before the operation aborted. Never null; lost on Java serialization (diagnostics only). */
    private final transient List<AdapterFailure> failures;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in English, never null
     * @param failures the adapter failures recorded up to the abort, never null (may be empty
     *                 when the abort came from the size governance rather than an adapter)
     */
    public CustomAdapterCaptureException(String message, List<AdapterFailure> failures) {
        super(message);
        this.failures = List.copyOf(failures);
    }

    /** The adapter failures recorded up to the abort. Never null; possibly empty. */
    public List<AdapterFailure> failures() {
        return failures;
    }
}
