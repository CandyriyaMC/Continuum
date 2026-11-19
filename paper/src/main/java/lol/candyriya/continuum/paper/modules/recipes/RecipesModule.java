package lol.candyriya.continuum.paper.modules.recipes;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs the player's discovered recipe book (crafting book unlocks) across
 * backends. The recipes module.
 *
 * <p><b>What is synced.</b> Vanilla recipe knowledge is a single flat set:
 * the keys a player has discovered. The 26.3 platform exposes exactly that
 * ({@code HumanEntity#getDiscoveredRecipes()}) and nothing else — there is
 * no public "hidden"/"seen-but-not-learned" state to persist, so the
 * discovered set is the complete synchronizable surface. Keys are stored
 * verbatim as {@code namespace:path} strings, which keeps datapack and
 * modded namespaces ({@code my_mod:copper_gear}) intact end to end.</p>
 *
 * <p><b>Mirror semantics (exact replica).</b> Restore makes the player's
 * book on the target server equal the captured set, filtered by what the
 * target actually ships: captured keys missing locally are discovered,
 * locally discovered keys absent from the capture are forgotten. A union
 * policy (discover only, never forget) was considered and rejected — it
 * cannot propagate "forgotten recipes", so a recipe un-learned on one
 * backend would linger forever on every other backend and the book would
 * monotonically grow. Forgetting is destructive by nature (the book entry
 * disappears), but it is the only faithful encoding of "the player knows
 * what they should know", and the undiscover happens server-side in one
 * batched call.</p>
 *
 * <p><b>Unknown recipes on the target.</b> A captured key the target server
 * does not ship (modded vs vanilla mismatch, datapack drift) is skipped
 * with a warning and never fails the restore; the rest of the book is
 * mirrored normally. The same applies to local keys the target cannot
 * resolve when computing the forget set. Unknown keys are not dropped from
 * future snapshots — the captured set is always stored verbatim, so
 * switching back to a backend that ships them restores them.</p>
 *
 * <p><b>Batching and the client book.</b> All mutation happens in at most
 * two batched platform calls — one {@code discoverRecipes} for the missing
 * half, one {@code undiscoverRecipes} for the mirror half — and a single
 * server-wide {@code updateRecipes()} at the end, only when the restore
 * actually changed the book. A no-op restore broadcasts nothing.</p>
 *
 * <p><b>Thread affinity.</b> Per the module contract, {@link #capture},
 * {@link #validate} and {@link #restore} are invoked on the thread that owns
 * the player — the server main thread on Paper, the entity's region thread
 * on Folia. Reading the book, probing the server's recipe map and writing
 * the book are all entity/server state available on that thread. No thread
 * hops happen inside this module. {@code updateRecipes()} sends packets to
 * all clients; it is invoked synchronously on the same thread, at most once
 * per restore, mirroring how the platform expects recipe data to be
 * re-published (e.g. after recipe registration on the main thread).</p>
 *
 * <p><b>Size cap.</b> {@value RecipesPayload#MAX_RECIPES} keys per snapshot
 * — far above anything vanilla or modded servers produce in practice.
 * Capture refuses to persist more (loud failure, never a truncated
 * snapshot); validation refuses to restore it.</p>
 */
public final class RecipesModule implements SyncModule<Player, RecipesPayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RecipesModule.class);

    /** The module id this module registers as; also exposed for registration wiring. */
    public static final String MODULE_ID = BuiltinModules.RECIPES;

    @Override
    public @NotNull String id() {
        return MODULE_ID;
    }

    @Override
    public int version() {
        return RecipesPayload.FORMAT;
    }

    @Override
    public @NotNull List<String> dependencies() {
        // recipe knowledge has no ordering requirement against other modules
        return List.of();
    }

    @Override
    public @NotNull CompletionStage<RecipesPayload> capture(@NotNull Player player) {
        try {
            PlayerRecipeOps ops = new BukkitPlayerRecipeOps(player);
            // throws on invalid keys or an oversized book — a broken capture must never
            // produce a half-valid snapshot, it must fail visibly instead
            List<String> sorted = RecipeKeys.normalizeCapture(ops.discoveredKeys());
            return CompletableFuture.completedFuture(new RecipesPayload(RecipesPayload.FORMAT, sorted));
        } catch (RuntimeException e) {
            var failure = new IllegalStateException("failed to capture recipes for player " + player.getUniqueId(), e);
            LOGGER.error("capture failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public @NotNull CompletionStage<Void> restore(@NotNull Player player, @NotNull RecipesPayload data) {
        try {
            PlayerRecipeOps ops = new BukkitPlayerRecipeOps(player);
            ServerRecipeLookup lookup = new BukkitServerRecipeLookup(player.getServer());
            RecipeRestoreEngine.restore(data, ops, lookup);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            // platform threw mid-restore; the delta may be partially applied — the next capture heals it
            var failure = new IllegalStateException("failed to restore recipes for player " + player.getUniqueId(), e);
            LOGGER.error("restore failed for player {}", player.getUniqueId(), e);
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public @NotNull CompletionStage<ValidationResult> validate(@NotNull Player player, @NotNull RecipesPayload data) {
        return CompletableFuture.completedFuture(RecipesValidation.validate(data));
    }
}
