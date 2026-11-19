package lol.candyriya.continuum.paper.modules.recipes;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * Thin seam between the module and a live player entity for recipe-book
 * operations.
 *
 * <p>String-based on purpose: keys cross the seam as {@code namespace:path}
 * so the whole planning/restoring pipeline is testable without a running
 * server. The production implementation parses each string into a
 * {@code NamespacedKey} right at the boundary — strings that reached this
 * far have passed strict validation, so parsing cannot fail; a parse failure
 * at the boundary is a bug and is thrown, never papered over.</p>
 *
 * <p>All methods run on the thread that owns the player (server main thread
 * on Paper, the entity's region thread on Folia), same affinity as the
 * module callbacks. Lookups must be served from the server's recipe map,
 * not from a world scan — {@code hasRecipe} is an O(1)-ish probe.</p>
 */
public interface PlayerRecipeOps {

    /**
     * Current discovered recipe keys of the player (the book state).
     *
     * @return immutable set of {@code namespace:path} strings; never null, may be empty
     */
    @NotNull
    Set<String> discoveredKeys();

    /**
     * Discovers a batch of recipes. Unknown keys are silently ignored by the
     * platform — this module never relies on that (unknowns are filtered by
     * the server lookup first), the return value reports the true delta.
     *
     * @param keys valid, duplicate-free keys to discover; never null, may be empty (0 returned)
     * @return the number of recipes newly added to the book
     */
    int discover(@NotNull List<String> keys);

    /**
     * Forgets a batch of recipes — the mirror half of the restore. Silently
     * ignores keys the player has not discovered; the return value reports
     * the true delta.
     *
     * @param keys valid, duplicate-free keys to forget; never null, may be empty (0 returned)
     * @return the number of recipes removed from the book
     */
    int undiscover(@NotNull List<String> keys);
}
