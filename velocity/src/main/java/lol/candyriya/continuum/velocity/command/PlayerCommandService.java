package lol.candyriya.continuum.velocity.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend-acting player commands: the five operations a proxy cannot perform
 * by itself because the player's authoritative state lives on the backend
 * holding them.
 *
 * <p>The contract for implementations: they ask that backend to run the
 * operation on the player's live state and report the outcome. The proxy
 * never mutates player state directly and never claims success before the
 * backend confirms it. {@link PlayerCommand#RESTORE} is destructive -
 * replacing live state with a stored snapshot - so implementations must
 * surface the destructive nature to the caller (an explicit confirmation
 * step in the command surface is the expected shape); silently restoring
 * is not acceptable.</p>
 *
 * <p>Callers must handle {@link PlayerCommandResult#executed()} being false
 * by reporting the reason to the operator, never by retrying blindly.</p>
 */
public interface PlayerCommandService {

    /** The backend-acting operations, named as the command sub-verbs. */
    enum PlayerCommand {
        /** Capture and store the player's state now. */
        SAVE,
        /** Capture a restore point of the player's state. */
        SNAPSHOT,
        /** Re-read the player's stored state into the live session. */
        RELOAD,
        /** Restore live state from a stored snapshot. Destructive. */
        RESTORE,
        /** List the player's stored snapshots. */
        HISTORY
    }

    /**
     * Outcome of one operation.
     *
     * @param executed true when the backend accepted and ran the operation;
     *                 false when it could not run (no handler connected, refused, failed)
     * @param detail   fixed, human-readable explanation of the outcome; never null, never blank
     */
    record PlayerCommandResult(boolean executed, String detail) {

        public PlayerCommandResult {
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }

        public static PlayerCommandResult executed(String detail) {
            return new PlayerCommandResult(true, detail);
        }

        public static PlayerCommandResult notExecuted(String detail) {
            return new PlayerCommandResult(false, detail);
        }
    }

    /**
     * Asks the backend holding {@code targetId} to run {@code command}.
     *
     * @param command    the operation to run
     * @param targetId   the player's uuid
     * @param targetName the player's last-known name, for operator-facing output
     */
    PlayerCommandResult execute(PlayerCommand command, UUID targetId, String targetName);
}
