package lol.candyriya.continuum.paper.modules.attributes;

/**
 * Raised when the module cannot do its job at all (capture read nothing but errors,
 * or a restore payload carried entries and not one of them was usable). Never used
 * for per-attribute problems — those are skipped with a warning.
 */
final class AttributesModuleException extends RuntimeException {

    AttributesModuleException(String message) {
        super(message);
    }

    AttributesModuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
