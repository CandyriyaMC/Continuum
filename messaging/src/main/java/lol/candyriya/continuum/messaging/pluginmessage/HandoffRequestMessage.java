package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend asks the proxy to move a player to another backend.
 *
 * <p>The proxy answers with a {@link RouteResultMessage} reply carrying the
 * same correlation id. The player uuid is advisory — the proxy derives the
 * acting player from the connection, never from this payload — but it is
 * validated anyway: a nil uuid is broken input, not a real player.</p>
 *
 * @param player       uuid of the player to move; must not be nil
 * @param targetServer name of the target backend as registered on the proxy;
 *                     lowercase, dotted/underscored, at most 64 characters
 * @param reason       free-form context for logs; never empty-null, may be
 *                     empty, at most 256 characters
 */
public record HandoffRequestMessage(UUID player, String targetServer, String reason) implements BackendToProxyMessage {

    public HandoffRequestMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(targetServer, "targetServer must not be null");
        Objects.requireNonNull(reason, "reason must not be null (use \"\" for no context)");
    }
}
