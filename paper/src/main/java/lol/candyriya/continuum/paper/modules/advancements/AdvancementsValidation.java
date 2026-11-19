package lol.candyriya.continuum.paper.modules.advancements;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structural checks on a decoded advancements payload, shared between the module's
 * {@code validate} step and anything else that wants to know whether a payload is
 * sane enough to apply. No platform calls — everything arrives as arguments, so the
 * whole check is unit-testable without a server.
 *
 * <p>This is deliberately shallow. It rejects a payload only when the payload itself
 * is untrustworthy: wrong format, negative timestamps, or a size that only a corrupt
 * writer would produce. Everything that can be tolerated is tolerated at restore time,
 * per entry — an advancement the target does not know, a captured criterion the target
 * does not declare — because one odd entry must never take the whole progress restore
 * down with it.</p>
 *
 * <p>Note that duplicate criteria are not rejected: they carry no ambiguity (the
 * restore math is set-based), so they cost nothing.</p>
 *
 * <p>Criteria subset issues are warn-only: an {@code awarded_at} entry naming a
 * criterion absent from {@code awarded_criteria} is logged and tolerated, never
 * rejected. The snapshot is not less applicable — restore does not read
 * {@code awarded_at} at all, and one odd entry must never take the whole progress
 * restore down with it.</p>
 */
public final class AdvancementsValidation {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementsValidation.class);

    private AdvancementsValidation() {
    }

    /**
     * Checks {@code data} for structural sanity.
     *
     * @param data the decoded payload, never null
     * @return {@link ValidationResult#ok()} when the payload may be applied, otherwise
     *         an invalid result with an English reason
     */
    public static @NotNull ValidationResult check(@NotNull AdvancementsPayload data) {
        if (data.format() != AdvancementsPayload.FORMAT) {
            return ValidationResult.invalid("unsupported advancements payload format " + data.format()
                    + " (expected " + AdvancementsPayload.FORMAT + ")");
        }
        if (data.capturedAtEpochMilli() < 0) {
            return ValidationResult.invalid("captured_at_epoch_milli is negative (" + data.capturedAtEpochMilli() + ")");
        }
        if (data.advancements().size() > AdvancementsPayload.MAX_ADVANCEMENTS) {
            return ValidationResult.invalid("advancements list holds " + data.advancements().size()
                    + " entries, max is " + AdvancementsPayload.MAX_ADVANCEMENTS);
        }
        for (int i = 0; i < data.advancements().size(); i++) {
            AdvancementEntry entry = data.advancements().get(i);
            if (!AdvancementKeys.isValid(entry.key())) {
                return ValidationResult.invalid("advancement at index " + i + " has an invalid key: '"
                        + shorten(entry.key()) + "'");
            }
            if (entry.awardedCriteria().size() > AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT) {
                return ValidationResult.invalid("advancement '" + entry.key() + "' lists "
                        + entry.awardedCriteria().size() + " awarded criteria, max is "
                        + AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT);
            }
            for (String criterion : entry.awardedCriteria()) {
                if (criterion == null || criterion.isBlank()) {
                    return ValidationResult.invalid("advancement '" + entry.key() + "' lists a blank awarded criterion");
                }
            }
            for (var e : entry.awardedAt().entrySet()) {
                if (e.getValue() == null || e.getValue() < 0) {
                    return ValidationResult.invalid("advancement '" + entry.key() + "' has a negative award date for criterion '"
                            + shorten(e.getKey()) + "'");
                }
                if (!entry.awardedCriteria().contains(e.getKey())) {
                    // warn-only: restore never reads awarded_at, so a snapshot with this
                    // inconsistency still applies cleanly; log it and move on
                    LOGGER.warn("advancement '{}' has an awarded_at entry for criterion '{}' that is not in its awarded_criteria; ignoring it",
                            entry.key(), shorten(e.getKey()));
                }
            }
        }
        return ValidationResult.ok();
    }

    /** Keeps corrupt identifiers out of log lines and validation messages at a sane size. */
    private static @NotNull String shorten(@NotNull String s) {
        return s.length() <= 64 ? s : s.substring(0, 61) + "...";
    }
}
