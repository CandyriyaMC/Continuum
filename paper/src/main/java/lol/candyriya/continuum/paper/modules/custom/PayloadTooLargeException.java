package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.ModuleException;

import java.io.Serial;

/**
 * Size governance hard error: the custom module's payload exceeds the configured cap.
 * Thrown instead of persisting or applying an oversized payload — the snapshot layer
 * still enforces its own limits on top of this one; this one exists so the caller gets a
 * typed, actionable exception instead of a generic serialization failure.
 *
 * @since 1.0.0
 */
public final class PayloadTooLargeException extends ModuleException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Bytes the payload actually measured. */
    private final int actualBytes;
    /** Bytes the cap allowed. */
    private final int capBytes;

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in English, never null
     * @param actualBytes the measured payload size
     * @param capBytes the configured cap that was exceeded
     */
    public PayloadTooLargeException(String message, int actualBytes, int capBytes) {
        super(message);
        this.actualBytes = actualBytes;
        this.capBytes = capBytes;
    }

    /** Bytes the payload actually measured. */
    public int actualBytes() {
        return actualBytes;
    }

    /** Bytes the cap allowed. */
    public int capBytes() {
        return capBytes;
    }
}
