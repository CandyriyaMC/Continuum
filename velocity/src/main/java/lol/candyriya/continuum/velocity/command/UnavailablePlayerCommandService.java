package lol.candyriya.continuum.velocity.command;

import java.util.Objects;
import java.util.UUID;

/**
 * The production wiring for {@link PlayerCommandService} until the backend
 * command channel exists.
 *
 * <p>The proxy cannot run these operations on its own - the player's
 * authoritative state lives on the backend holding them, and only that
 * backend can capture or restore it. A command channel from the proxy to
 * the backends for these operations has not been built yet, so every call
 * refuses with the precise reason instead of pretending to have worked.
 * This class is deliberately the only default; wiring a real implementation
 * in place of it is one line at the registration site.</p>
 */
public final class UnavailablePlayerCommandService implements PlayerCommandService {

    /** Fixed reason shown to operators; kept free of markup so it can be printed verbatim. */
    public static final String NOT_CONNECTED_DETAIL =
            "is not available in this build: it runs on the backend that holds the player, "
                    + "and no backend handler for it is connected yet. Nothing was changed.";

    @Override
    public PlayerCommandResult execute(PlayerCommand command, UUID targetId, String targetName) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(targetName, "targetName");
        return PlayerCommandResult.notExecuted(NOT_CONNECTED_DETAIL);
    }
}
