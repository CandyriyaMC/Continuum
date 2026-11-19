package lol.candyriya.continuum.paper.modules.custom;

/**
 * Thrown by the guarded player when an adapter calls it after missing its deadline.
 * Adapters that hold a guarded reference will typically see this as a plain
 * {@link RuntimeException} from inside their own code — which is the point: after a
 * timeout, further writes to a live player are worse than useless.
 *
 * @since 1.0.0
 */
public final class AdapterCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a standard message. */
    public AdapterCancelledException(String message) {
        super(message);
    }
}
