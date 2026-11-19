package lol.candyriya.continuum.paper.gate;

import java.util.UUID;

/**
 * What a spawn policy needs to decide a spawn location. Carries only stable,
 * non-entity data — policies run on the async spawn-decision thread and must
 * stay there.
 *
 * @param playerId  the joining player's id
 * @param playerName the joining player's name (may be empty when not yet known; never null)
 * @param newPlayer true when the player has no profile history on this network (first join)
 */
public record SpawnContext(UUID playerId, String playerName, boolean newPlayer) {

    public SpawnContext {
        if (playerId == null) {
            throw new NullPointerException("playerId must not be null");
        }
        if (playerName == null) {
            playerName = "";
        }
    }
}
