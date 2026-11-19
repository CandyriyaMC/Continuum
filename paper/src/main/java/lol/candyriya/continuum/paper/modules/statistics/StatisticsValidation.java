package lol.candyriya.continuum.paper.modules.statistics;

import lol.candyriya.continuum.api.module.ValidationResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validation for {@link StatisticsPayload}.
 *
 * <p>Validation is deliberately structural, not semantic:</p>
 * <ul>
 *   <li>the format must be the version this build writes;</li>
 *   <li>every entry needs a syntactically valid qualified key (see {@link StatKey});</li>
 *   <li>values must be non-negative and fit the platform's int counter range;</li>
 *   <li>keys must be unique;</li>
 *   <li>the list must not exceed {@link StatisticsPayload#MAX_ENTRIES}.</li>
 * </ul>
 *
 * <p>What is <b>not</b> rejected: keys that are syntactically fine but unknown
 * to this build (a snapshot captured on a newer server carries materials and
 * entity types this build cannot resolve). Restore skips those with a warning
 * and applies everything else — a newer snapshot must not brick an older
 * backend. Unresolvable keys are a restore-time concern, not a validity concern.</p>
 */
public final class StatisticsValidation {

    private StatisticsValidation() {
        // static validation only
    }

    public static ValidationResult validate(StatisticsPayload payload) {
        if (payload == null) {
            return ValidationResult.invalid("payload is null");
        }
        if (payload.format() != StatisticsPayload.FORMAT) {
            return ValidationResult.invalid("unsupported format: " + payload.format());
        }
        List<StatisticsPayload.Entry> stats = payload.stats();
        if (stats.size() > StatisticsPayload.MAX_ENTRIES) {
            return ValidationResult.invalid("too many stat entries: " + stats.size());
        }
        Set<String> seen = new HashSet<>(stats.size() * 2);
        for (int i = 0; i < stats.size(); i++) {
            StatisticsPayload.Entry entry = stats.get(i);
            if (entry == null) {
                return ValidationResult.invalid("stat entry #" + i + " is null");
            }
            String key = entry.key();
            if (key == null || key.isBlank()) {
                return ValidationResult.invalid("stat entry #" + i + " has a blank key");
            }
            if (!StatKey.parse(key).isPresent()) {
                return ValidationResult.invalid("stat entry #" + i + " has a malformed key: " + key);
            }
            if (entry.value() < 0) {
                return ValidationResult.invalid("stat entry '" + key + "' has a negative value: " + entry.value());
            }
            if (entry.value() > Integer.MAX_VALUE) {
                return ValidationResult.invalid("stat entry '" + key + "' exceeds the counter range: " + entry.value());
            }
            if (!seen.add(key)) {
                return ValidationResult.invalid("duplicate stat key: " + key);
            }
        }
        return ValidationResult.ok();
    }
}
