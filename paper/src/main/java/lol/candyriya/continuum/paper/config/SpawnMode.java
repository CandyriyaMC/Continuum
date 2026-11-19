package lol.candyriya.continuum.paper.config;

/**
 * How the backend decides where a player lands on join when no snapshot location is in play yet.
 */
public enum SpawnMode {
    /** Spawn the player at the configured world's spawn point. */
    SERVER_SPAWN,
    /** Spawn at the location from the player's last snapshot; falls back to the server spawn when unavailable. */
    LAST_KNOWN_LOCATION,
    /** Do not override the spawn location; whatever the platform decides stands. */
    DISABLED
}
