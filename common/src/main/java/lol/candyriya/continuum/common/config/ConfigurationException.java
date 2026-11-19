package lol.candyriya.continuum.common.config;

import lol.candyriya.continuum.common.error.ContinuumException;
import lol.candyriya.continuum.common.error.ErrorCode;

import java.util.List;

/**
 * One failure, every violation. The message carries the full problem list —
 * fixing config errors one restart at a time is how operators lose weekends,
 * so all problems are reported in one go.
 *
 * <p>Violations name key paths and rules, never the offending values, so
 * secrets stay out of logs even when the config is broken.</p>
 */
public final class ConfigurationException extends ContinuumException {

    private final List<String> violations;

    public ConfigurationException(List<String> violations) {
        super(ErrorCode.CONFIG_INVALID, buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public ConfigurationException(String violation, Throwable cause) {
        super(ErrorCode.CONFIG_INVALID, "invalid configuration:\n - " + violation, cause);
        this.violations = List.of(violation);
    }

    /** All problems found, key path first. Unmodifiable. */
    public List<String> violations() {
        return violations;
    }

    private static String buildMessage(List<String> violations) {
        var sb = new StringBuilder("invalid configuration:\n");
        for (String violation : violations) {
            sb.append(" - ").append(violation).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
