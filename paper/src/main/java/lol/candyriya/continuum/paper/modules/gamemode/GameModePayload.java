package lol.candyriya.continuum.paper.modules.gamemode;

/**
 * Wire payload for {@code continuum.gamemode} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys, enums as names). Component names are part of the
 * stable wire contract — renaming one is a schema bump. Example payload:</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "game_mode": "SURVIVAL",
 *   "allow_flight": false,
 *   "flying": false,
 *   "fly_speed": 0.1,
 *   "walk_speed": 0.2,
 *   "gliding": false,
 *   "sneaking": false,
 *   "sprinting": true
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code game_mode} is the {@code org.bukkit.GameMode} enum constant
 *       name, stored as a plain string ({@code CREATIVE}, {@code SURVIVAL},
 *       {@code ADVENTURE}, {@code SPECTATOR}). A snapshot carrying a constant
 *       this server does not know (captured from a newer build) is not a
 *       validation failure — the game mode is skipped at restore with a
 *       warning and the rest of the payload still applies.</li>
 *   <li>{@code fly_speed} / {@code walk_speed} are client ability speeds;
 *       the platform accepts the range [-1.0, 1.0] and throws outside it.
 *       Finite out-of-range values are clamped at restore (restorable, do
 *       not fail the payload); non-finite values are rejected at
 *       validation.</li>
 *   <li>{@code flying} is only applied when the player is allowed to fly
 *       after the allow-flight write — the target server forbidding flight
 *       (permission-based or otherwise) wins over the snapshot.</li>
 * </ul>
 *
 * @param format     payload format version, always {@value #FORMAT}
 * @param gameMode   {@code GameMode} enum constant name
 * @param allowFlight whether the player may fly
 * @param flying     whether the player is currently flying
 * @param flySpeed   fly ability speed, [-1.0, 1.0]
 * @param walkSpeed  walk ability speed, [-1.0, 1.0]
 * @param gliding    elytra gliding flag
 * @param sneaking   sneaking flag
 * @param sprinting  sprinting flag
 */
public record GameModePayload(
        int format,
        String gameMode,
        boolean allowFlight,
        boolean flying,
        float flySpeed,
        float walkSpeed,
        boolean gliding,
        boolean sneaking,
        boolean sprinting) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;
}
