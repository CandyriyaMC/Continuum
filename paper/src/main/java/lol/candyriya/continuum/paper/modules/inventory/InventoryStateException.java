package lol.candyriya.continuum.paper.modules.inventory;

/**
 * Failure with module/section context attached. Anything captured or restored that does
 * not line up ends up here, so the platform can report one precise cause instead of
 * a bare stack trace from somewhere deep in the stack.
 */
final class InventoryStateException extends RuntimeException {

    InventoryStateException(String message) {
        super(message);
    }

    InventoryStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
