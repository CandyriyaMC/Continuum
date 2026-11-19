package lol.candyriya.continuum.paper.modules.gamemode;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Syncs the game mode and the client ability flags that travel with it:
 * allow-flight, flying, fly speed, walk speed, gliding, sneaking, sprinting.
 *
 * <p><b>Game mode by name.</b> The enum constant name is stored as a string.
 * {@link GameMode#valueOf(String)} is applied leniently at restore: a name
 * this server does not recognize is logged and skipped — the rest of the
 * payload (flags, speeds) still applies. That makes a snapshot captured on a
 * newer build safe to restore on an older one.</p>
 *
 * <p><b>Flight is conditional.</b> {@code flying} is restored only when the
 * player is allowed to fly <em>after</em> the allow-flight write — a target
 * server forbidding flight wins over the snapshot, and no flight window is
 * opened even for a 1-tick interval. The whole flight trio (allow flight,
 * flying, fly speed) can be excluded from synchronization by constructing
 * the module with {@code restoreFlight = false}; game mode and the movement
 * flags have the same kind of switch. Capture always records the full
 * truth, exclusions are restore-side only.</p>
 *
 * <p><b>Speeds.</b> The platform accepts [-1.0, 1.0] and throws outside it;
 * finite out-of-range values are clamped at restore (see {@code GameModeMath}),
 * anything non-finite fails validation. Negative values are meaningful
 * (reverse-direction movement), which is why the bound is the API's range,
 * not 0..1.</p>
 *
 * <p><b>Movement flags.</b> Sneak/sprint/glide are entity pose flags. They
 * are applied as stored and degrade harmlessly on servers that manage them
 * differently; gliding self-corrects on the next tick without an elytra.</p>
 *
 * <p>Thread-safety: read/written on the player's own thread; this class keeps
 * no mutable state.</p>
 */
public final class GameModeModule implements SyncModule<Player, GameModePayload> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameModeModule.class);

    private final GameModeRestoreConfig config;

    /** Creates the module restoring everything (default). */
    public GameModeModule() {
        this(true, true, true);
    }

    /**
     * Creates the module with restore-side feature switches.
     *
     * @param restoreGameMode      apply the game mode write
     * @param restoreFlight        apply allow flight, flying, fly speed; disable on servers that forbid flight management
     * @param restoreMovementFlags apply gliding, sneaking, sprinting
     */
    public GameModeModule(boolean restoreGameMode, boolean restoreFlight, boolean restoreMovementFlags) {
        this.config = new GameModeRestoreConfig(restoreGameMode, restoreFlight, restoreMovementFlags);
    }

    @Override
    public String id() {
        return BuiltinModules.GAMEMODE;
    }

    @Override
    public int version() {
        return GameModePayload.FORMAT;
    }

    @Override
    public List<String> dependencies() {
        return List.of();
    }

    @Override
    public CompletionStage<GameModePayload> capture(Player player) {
        return CompletableFuture.completedFuture(new GameModePayload(
                GameModePayload.FORMAT,
                player.getGameMode().name(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getFlySpeed(),
                player.getWalkSpeed(),
                player.isGliding(),
                player.isSneaking(),
                player.isSprinting()));
    }

    @Override
    public CompletionStage<Void> restore(Player player, GameModePayload data) {
        GameModeApply.apply(data, new PlayerGameModeOps(player), config);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<ValidationResult> validate(Player player, GameModePayload data) {
        return CompletableFuture.completedFuture(GameModeValidation.validate(data));
    }

    /** Thin pass-through from the pure restore logic to the live entity. */
    private record PlayerGameModeOps(Player player) implements GameModeOps {

        @Override
        public void setGameMode(String gameModeName) {
            final GameMode parsed;
            try {
                parsed = GameMode.valueOf(gameModeName);
            } catch (IllegalArgumentException | NullPointerException e) {
                // unknown or malformed constant — keep flags/speeds, skip the mode
                LOGGER.warn("unknown game mode '{}' in snapshot; skipping the game mode write", gameModeName);
                return;
            }
            player.setGameMode(parsed);
        }

        @Override
        public void setAllowFlight(boolean allow) {
            player.setAllowFlight(allow);
        }

        @Override
        public boolean allowFlight() {
            return player.getAllowFlight();
        }

        @Override
        public void setFlying(boolean flying) {
            player.setFlying(flying);
        }

        @Override
        public void setFlySpeed(float speed) {
            player.setFlySpeed(speed);
        }

        @Override
        public void setWalkSpeed(float speed) {
            player.setWalkSpeed(speed);
        }

        @Override
        public void setGliding(boolean gliding) {
            player.setGliding(gliding);
        }

        @Override
        public void setSneaking(boolean sneaking) {
            player.setSneaking(sneaking);
        }

        @Override
        public void setSprinting(boolean sprinting) {
            player.setSprinting(sprinting);
        }
    }
}
