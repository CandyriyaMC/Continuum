package lol.candyriya.continuum.paper.modules.gamemode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a validated {@link GameModePayload} in the documented order:
 *
 * <ol>
 *   <li>game mode — first, so every flag that follows is written under the
 *       mode it belongs to (creative/spectator allow flight, survival does
 *       not),</li>
 *   <li>allow flight — then the live flag is re-read,</li>
 *   <li>flying — only when the live allow-flight read says yes: a target
 *       server that forbids flight (permission plugins, custom flight
 *       managers) wins over the snapshot, and a 1-tick flight window is
 *       never opened,</li>
 *   <li>fly speed, then walk speed (always applied — vanilla walk speed has
 *       no permission semantics and there is nothing to forbid),</li>
 *   <li>gliding, sneaking, sprinting — last. Sneak/sprint are entity pose
 *       flags and gliding self-corrects next tick without an elytra; they
 *       degrade harmlessly on any target server.</li>
 * </ol>
 *
 * <p>Nothing here teleports the player and no combat/physics state is
 * touched. All writes are absolute values; re-applying the same payload is
 * idempotent.</p>
 */
final class GameModeApply {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameModeApply.class);

    private GameModeApply() {
        // static access only
    }

    static void apply(GameModePayload data, GameModeOps ops, GameModeRestoreConfig config) {
        if (config.restoreGameMode()) {
            ops.setGameMode(data.gameMode());
        } else {
            LOGGER.debug("game mode restore disabled by module config; skipping {}", data.gameMode());
        }

        if (config.restoreFlight()) {
            ops.setAllowFlight(data.allowFlight());
            boolean granted = ops.allowFlight();
            if (granted) {
                ops.setFlying(data.flying());
            } else {
                LOGGER.info("flight restore skipped: player is not allowed to fly on this server (allow_flight=false)");
            }
            ops.setFlySpeed(GameModeMath.resolveSpeed(data.flySpeed()));
            if (isOutOfRange(data.flySpeed())) {
                LOGGER.warn("fly speed {} was clamped into [{}, {}]", data.flySpeed(), GameModeMath.SPEED_MIN, GameModeMath.SPEED_MAX);
            }
        } else {
            LOGGER.debug("flight restore disabled by module config; skipping allow_flight/flying/fly_speed");
        }

        ops.setWalkSpeed(GameModeMath.resolveSpeed(data.walkSpeed()));
        if (isOutOfRange(data.walkSpeed())) {
            LOGGER.warn("walk speed {} was clamped into [{}, {}]", data.walkSpeed(), GameModeMath.SPEED_MIN, GameModeMath.SPEED_MAX);
        }

        if (config.restoreMovementFlags()) {
            ops.setGliding(data.gliding());
            ops.setSneaking(data.sneaking());
            ops.setSprinting(data.sprinting());
        } else {
            LOGGER.debug("movement flag restore disabled by module config; skipping gliding/sneaking/sprinting");
        }
    }

    private static boolean isOutOfRange(float speed) {
        return !Float.isNaN(speed) && !Float.isInfinite(speed)
                && (speed < GameModeMath.SPEED_MIN || speed > GameModeMath.SPEED_MAX);
    }
}
