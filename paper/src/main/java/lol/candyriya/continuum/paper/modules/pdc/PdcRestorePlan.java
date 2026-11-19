package lol.candyriya.continuum.paper.modules.pdc;

import java.util.List;
import java.util.Objects;

/**
 * What to apply on restore, decided without touching a live container.
 *
 * @param actions             entries to apply, in payload order
 * @param skippedDenied       entries skipped because their namespace is on the hard deny-list
 * @param skippedIllegalKey   entries skipped because the key is not a legal namespaced key
 * @param skippedUndecodable  entries skipped because a binary value is not valid base64
 * @param skippedTooLarge     entries skipped because they exceed the per-value cap
 * @param skippedBudgetExhausted entries skipped because the total restore budget was already spent
 */
public record PdcRestorePlan(
        List<PdcEntry> actions,
        int skippedDenied,
        int skippedIllegalKey,
        int skippedUndecodable,
        int skippedTooLarge,
        int skippedBudgetExhausted) {

    public PdcRestorePlan {
        Objects.requireNonNull(actions, "actions");
        actions = List.copyOf(actions);
    }

    /** Total entries accounted for, applied or skipped. */
    public int totalEntries() {
        return actions.size() + skippedDenied + skippedIllegalKey + skippedUndecodable
                + skippedTooLarge + skippedBudgetExhausted;
    }
}
