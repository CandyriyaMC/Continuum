package lol.candyriya.continuum.paper.modules.location;

import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * {@link WorldLookup} backed by the live server. Only loaded worlds are visible —
 * an unloaded world answers "no", which is exactly what the restore path wants
 * (it must not force-load a world just to teleport someone into it).
 *
 * <p>Stateless; safe to call from the player's entity thread on both Paper and Folia.</p>
 */
public final class BukkitWorldLookup implements WorldLookup {

    /** Shared instance — there is nothing to configure. */
    public static final BukkitWorldLookup INSTANCE = new BukkitWorldLookup();

    private BukkitWorldLookup() {
        // singleton
    }

    @Override
    public boolean exists(String worldName) {
        try {
            return Bukkit.getWorld(worldName) != null;
        } catch (RuntimeException e) {
            // an exception here must not corrupt the restore — "cannot tell" is a skip, not a crash
            return false;
        }
    }

    @Override
    public String environmentOf(String worldName) {
        try {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : PositionSnapshot.environmentOf(world);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
