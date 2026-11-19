package lol.candyriya.continuum.paper.gate;

import org.bukkit.Location;

import org.jetbrains.annotations.Nullable;

/**
 * Decides where a player spawns on join.
 *
 * <p>Implementations run on the async spawn-decision thread — before the player
 * exists in the world — so they must never touch entities, chunks or world
 * mutation. Reading spawn coordinates and handing back a fresh {@link Location}
 * is all they may do.</p>
 *
 * <p>Returning {@code null} means "no opinion": the platform's default spawn
 * stands. Implementations are stateless or externally synchronized; the gate
 * calls them from any thread.</p>
 */
public interface SpawnPolicy {

    /**
     * Decide the spawn location.
     *
     * @return a location for the player, or {@code null} to keep the platform's decision
     */
    @Nullable Location decide(SpawnContext context);
}
