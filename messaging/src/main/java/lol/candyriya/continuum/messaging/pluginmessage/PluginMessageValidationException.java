package lol.candyriya.continuum.messaging.pluginmessage;

import lol.candyriya.continuum.common.error.ErrorCode;

import java.util.List;

/**
 * Semantic validation failed: bounds, formats or cross-field rules. Carries
 * every violation found, in the order they were detected — nothing is
 * truncated, nothing is left for the caller to guess.
 */
public final class PluginMessageValidationException extends PluginMessageException {

    private final List<String> violations;

    public PluginMessageValidationException(List<String> violations) {
        super(ErrorCode.VALIDATION_FAILED, String.join("; ", violations));
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        this.violations = List.copyOf(violations);
    }

    /** All violations found, in detection order. Never empty. */
    public List<String> violations() {
        return violations;
    }
}
