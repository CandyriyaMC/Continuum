package lol.candyriya.continuum.api.player;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.util.UUID;

/**
 * Thrown (or used to complete a stage exceptionally) when an operation requires the
 * player to be online on this backend, but they are not — for example a forced capture
 * of a player who has since left.
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class PlayerNotOnlineException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The player that was not online. */
    private final UUID playerId;

    /**
     * Creates the exception.
     *
     * @param playerId the player that was not online, never null
     */
    public PlayerNotOnlineException(@NotNull UUID playerId) {
        super("player " + playerId + " is not online on this backend");
        this.playerId = playerId;
    }

    /**
     * The player that was not online.
     *
     * @return the player UUID, never null
     */
    @NotNull
    public UUID playerId() {
        return playerId;
    }
}
