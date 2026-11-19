package lol.candyriya.continuum.paper.modules.recipes;

import org.bukkit.NamespacedKey;
import org.bukkit.Server;

import org.jetbrains.annotations.NotNull;

/**
 * Bukkit-backed {@link ServerRecipeLookup}. {@code hasRecipe} probes the
 * server's recipe map (cheap); {@code updateRecipes()} resends the recipe
 * book to every connected client and is called at most once per restore,
 * only when the restore mutated something.
 */
final class BukkitServerRecipeLookup implements ServerRecipeLookup {

    private final Server server;

    BukkitServerRecipeLookup(Server server) {
        this.server = server;
    }

    @Override
    public boolean hasRecipe(@NotNull String key) {
        NamespacedKey parsed = NamespacedKey.fromString(key);
        // total contract: a key the platform cannot parse is a key the server does not ship
        return parsed != null && server.hasRecipe(parsed);
    }

    @Override
    public void updateRecipes() {
        server.updateRecipes();
    }
}
