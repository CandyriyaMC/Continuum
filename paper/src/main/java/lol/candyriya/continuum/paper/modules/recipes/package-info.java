/**
 * Recipe knowledge sync: the discovered crafting book of a player, kept
 * consistent across backends.
 *
 * <p>Payload is one flat, sorted, duplicate-free list of
 * {@code namespace:path} keys ({@link lol.candyriya.continuum.paper.modules.recipes.RecipesPayload}).
 * Restore mirrors it exactly — discover what is missing, forget what was
 * not captured — with unknown-to-target keys skipped with a warning rather
 * than failing the restore. Platform access goes through
 * {@link lol.candyriya.continuum.paper.modules.recipes.PlayerRecipeOps} and
 * {@link lol.candyriya.continuum.paper.modules.recipes.ServerRecipeLookup}
 * so the planning and validation logic stays unit-testable without a
 * server.</p>
 */
package lol.candyriya.continuum.paper.modules.recipes;
