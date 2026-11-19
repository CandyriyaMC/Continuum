package lol.candyriya.continuum.paper.modules.recipes;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bukkit-backed {@link PlayerRecipeOps}. Parses and re-parses keys right at
 * the seam — strings arriving here passed strict validation, so
 * {@code NamespacedKey.fromString} cannot legitimately return null; a null
 * anyway means a programming error and is thrown as such.
 */
final class BukkitPlayerRecipeOps implements PlayerRecipeOps {

    private final Player player;

    BukkitPlayerRecipeOps(Player player) {
        this.player = player;
    }

    @Override
    public @NotNull Set<String> discoveredKeys() {
        return player.getDiscoveredRecipes().stream()
                .map(NamespacedKey::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public int discover(@NotNull List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        return player.discoverRecipes(toKeys(keys));
    }

    @Override
    public int undiscover(@NotNull List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        return player.undiscoverRecipes(toKeys(keys));
    }

    private static List<NamespacedKey> toKeys(List<String> keys) {
        List<NamespacedKey> parsed = new ArrayList<>(keys.size());
        for (String key : keys) {
            NamespacedKey parsedKey = NamespacedKey.fromString(key);
            if (parsedKey == null) {
                // validated strings always parse — hitting this means the validation gate was bypassed
                throw new IllegalArgumentException("recipe key '" + key + "' failed to parse at the platform boundary");
            }
            parsed.add(parsedKey);
        }
        return parsed;
    }
}
