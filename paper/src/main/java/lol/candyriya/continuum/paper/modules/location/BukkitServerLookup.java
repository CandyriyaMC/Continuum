package lol.candyriya.continuum.paper.modules.location;

import org.bukkit.Bukkit;
import org.bukkit.World;

import org.jetbrains.annotations.Nullable;

/**
 * {@link ServerLookup} backed by the live server.
 */
public final class BukkitServerLookup implements ServerLookup {

    /** Shared instance — there is nothing to configure. */
    public static final BukkitServerLookup INSTANCE = new BukkitServerLookup();

    private BukkitServerLookup() {
        // singleton
    }

    @Override
    public @Nullable String defaultWorldName() {
        try {
            var worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                return null;
            }
            World first = worlds.getFirst();
            return first == null ? null : first.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
