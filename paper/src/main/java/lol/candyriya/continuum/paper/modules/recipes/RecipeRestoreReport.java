package lol.candyriya.continuum.paper.modules.recipes;

/**
 * Outcome of a restore, for logging and tests.
 *
 * @param discovered        recipes newly discovered on the player (delta actually applied)
 * @param undiscovered      recipes forgotten on the player (delta actually applied)
 * @param unknownToTarget   captured keys skipped because the target server does not know them
 * @param forgetSkippedUnknown local keys skipped because the target server does not know them
 * @param invalidCaptured   captured strings that failed strict validation and were ignored
 * @param duplicatesCollapsed captured duplicates collapsed before the diff
 * @param clientRefresh     whether the server-wide recipe-book refresh was triggered
 *                          (exactly once, and only when something was applied)
 */
public record RecipeRestoreReport(
        int discovered,
        int undiscovered,
        int unknownToTarget,
        int forgetSkippedUnknown,
        int invalidCaptured,
        int duplicatesCollapsed,
        boolean clientRefresh) {

    /** Total recipe keys applied to the player (discover + forget deltas). */
    public int mutations() {
        return discovered + undiscovered;
    }
}
