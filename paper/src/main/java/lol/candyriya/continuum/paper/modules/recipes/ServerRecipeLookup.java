package lol.candyriya.continuum.paper.modules.recipes;

import org.jetbrains.annotations.NotNull;

/**
 * Thin seam between the module and the server's recipe map.
 *
 * <p>Two responsibilities, deliberately on the same seam: probing whether the
 * target ships a recipe, and asking the server to resend the recipe book to
 * clients after a restore. Keeping both behind one interface is what lets
 * the whole restore path run in unit tests without a server — and the book
 * refresh must never happen by accident, so it is a method call, not a side
 * effect of {@code hasRecipe}.</p>
 *
 * <p>String-based like its player-side sibling; production implementations
 * parse at the boundary. Runs on the player's thread, same affinity as the
 * module callbacks.</p>
 */
public interface ServerRecipeLookup {

    /**
     * Whether the target server ships a recipe under {@code key}.
     *
     * <p>Contract is total: a key this server cannot parse reports
     * {@code false} (this server demonstrably does not ship it), never an
     * exception. The mirror planner relies on that for defensive filtering.</p>
     *
     * @param key expected to be a strict {@code namespace:path} string; malformed input yields false
     * @return true when the recipe exists on this server
     */
    boolean hasRecipe(@NotNull String key);

    /**
     * Resends the recipe data and the recipe book to every connected
     * client. Called at most once per restore, and only when the restore
     * actually changed the player's book — a no-op restore must not
     * broadcast anything.
     */
    void updateRecipes();
}
