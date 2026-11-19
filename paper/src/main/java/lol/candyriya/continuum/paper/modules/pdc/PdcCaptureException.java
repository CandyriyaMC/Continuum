package lol.candyriya.continuum.paper.modules.pdc;

/**
 * The snapshot was refused because the PDC contents exceed the size budget,
 * or a captured value cannot be serialized at all.
 *
 * <p>This is a deliberately fatal condition for the capture call - the module
 * reports it as {@code CAPTURE_FAILED} and the platform refuses to store a
 * partial snapshot. It is never thrown for a single skippable bad value;
 * those are counted and skipped.</p>
 */
public class PdcCaptureException extends RuntimeException {

    /** Creates the exception with a message, in English, never null. */
    public PdcCaptureException(String message) {
        super(message);
    }

    /** Creates the exception with a message and a cause. */
    public PdcCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
