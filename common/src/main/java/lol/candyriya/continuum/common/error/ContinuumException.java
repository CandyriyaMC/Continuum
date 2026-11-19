package lol.candyriya.continuum.common.error;

/**
 * Base class for every typed failure Continuum raises.
 *
 * <p>Unchecked by design: a platform main loop must not be forced to catch
 * domain failures it cannot handle. Callers that can recover match on
 * {@link #code()}; callers that cannot recover let it bubble to a boundary
 * that logs with context.</p>
 */
public abstract class ContinuumException extends RuntimeException {

    private final ErrorCode errorCode;

    protected ContinuumException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ContinuumException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The stable machine-readable code for this failure. */
    public final ErrorCode errorCode() {
        return errorCode;
    }

    /** Convenience dotted code string, equivalent to {@code errorCode().code()}. */
    public final String code() {
        return errorCode.code();
    }
}
