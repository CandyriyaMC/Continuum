package lol.candyriya.continuum.paper.modules.recipes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Restore pipeline shared by the module entrypoint and unit tests.
 *
 * <p>Mirrors the captured set onto the player in three steps: diff
 * ({@link RecipeMirrorPlanner}), apply (one batched {@code discoverRecipes}
 * call for the discover half, one batched {@code undiscoverRecipes} call for
 * the forget half), then a single {@code updateRecipes()} so clients stop
 * showing a stale book. The refresh happens only when the restore actually
 * mutated something — otherwise every join would blast a server-wide recipe
 * packet for zero change.</p>
 *
 * <p>Unknown recipes on the target (modded vs vanilla mismatch) are skipped
 * with a warning and never fail the restore; the captured set is preserved
 * untouched for backends that do know the keys. Any platform exception from
 * a batched operation propagates to the caller — the module turns it into a
 * failed restore future so Continuum records the failure.</p>
 *
 * <p><b>Corruption guard.</b> An explicit {@code recipes} list (even an
 * empty one — a legitimately emptied book) is the only state that may drive
 * an undiscover. A payload whose list is missing or the payload itself is
 * missing is rejected up front, never treated as "empty — forget everything".</p>
 */
final class RecipeRestoreEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecipeRestoreEngine.class);

    /** Cap on how many skipped unknown keys are listed in a single warning — keep logs readable. */
    private static final int UNKNOWN_SAMPLE = 10;

    private RecipeRestoreEngine() {
        // static access only
    }

    /**
     * Applies the mirror restore.
     *
     * @param data   the captured payload; must not be null and its list must not be null
     * @param ops    player-side adapter
     * @param lookup server-side adapter
     * @return what actually happened, for logging and tests
     * @throws IllegalArgumentException when the payload is null or its list is null (corrupt — never applied)
     * @throws RuntimeException         whatever the platform threw from a batched operation, propagated
     */
    static RecipeRestoreReport restore(RecipesPayload data, PlayerRecipeOps ops, ServerRecipeLookup lookup) {
        if (data == null) {
            // a missing payload must never reach the mirror math — an empty effective set
            // would undiscover the player's entire book
            throw new IllegalArgumentException("payload is null");
        }
        if (data.recipes() == null) {
            throw new IllegalArgumentException("payload recipes list is null — refusing to mirror an empty book");
        }

        Set<String> local = ops.discoveredKeys();
        MirrorPlan plan = RecipeMirrorPlanner.plan(data.recipes(), local, lookup::hasRecipe);

        if (!plan.invalidCaptured().isEmpty()) {
            // validation should have bounced these; if we got here anyway, say so loudly
            LOGGER.warn("ignoring {} invalid captured recipe key(s): {}",
                    plan.invalidCaptured().size(), sample(plan.invalidCaptured()));
        }
        if (!plan.unknownToTarget().isEmpty()) {
            LOGGER.warn("skipping {} recipe(s) not present on this server: {} (first {})",
                    plan.unknownToTarget().size(), sample(plan.unknownToTarget()), UNKNOWN_SAMPLE);
        }
        if (!plan.forgetSkippedUnknown().isEmpty()) {
            LOGGER.warn("cannot forget {} local recipe(s) unknown to this server: {} (first {})",
                    plan.forgetSkippedUnknown().size(), sample(plan.forgetSkippedUnknown()), UNKNOWN_SAMPLE);
        }
        if (plan.duplicatesCollapsed() > 0) {
            LOGGER.warn("collapsed {} duplicate captured recipe key(s) before the diff", plan.duplicatesCollapsed());
        }

        int discovered = plan.toDiscover().isEmpty() ? 0 : ops.discover(plan.toDiscover());
        int undiscovered = plan.toForget().isEmpty() ? 0 : ops.undiscover(plan.toForget());

        boolean refresh = plan.changed();
        if (refresh) {
            // exactly once, and only when we actually touched the book — anything else spams every client
            lookup.updateRecipes();
        }

        RecipeRestoreReport report = new RecipeRestoreReport(
                discovered,
                undiscovered,
                plan.unknownToTarget().size(),
                plan.forgetSkippedUnknown().size(),
                plan.invalidCaptured().size(),
                plan.duplicatesCollapsed(),
                refresh);
        LOGGER.debug("recipes restored: discovered={} undiscovered={} unknownToTarget={} forgetSkippedUnknown={} invalid={} clientRefresh={}",
                report.discovered(), report.undiscovered(), report.unknownToTarget(),
                report.forgetSkippedUnknown(), report.invalidCaptured(), report.clientRefresh());
        return report;
    }

    private static String sample(List<String> keys) {
        if (keys.size() <= UNKNOWN_SAMPLE) {
            return String.join(", ", keys);
        }
        return String.join(", ", keys.subList(0, UNKNOWN_SAMPLE)) + ", ...";
    }
}
