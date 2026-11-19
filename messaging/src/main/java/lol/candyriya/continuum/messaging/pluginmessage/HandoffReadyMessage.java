package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Target backend tells the proxy it is (or is not) ready to receive the
 * player. Sent unprompted once the target has warmed up — it is the one
 * proxy→backend reply trigger the other way around: the proxy answers with
 * the {@link SessionTokenMessage} the target needs for the incoming session.
 *
 * @param player uuid of the player that would land here; must not be nil
 * @param server this backend's own name as registered on the proxy;
 *               lowercase, dotted/underscored, at most 64 characters
 * @param ready  {@code true} when the player can be routed now; {@code false}
 *               declines the handoff (see {@code detail})
 * @param detail free-form context for logs and operators; may be empty,
 *               at most 256 characters
 */
public record HandoffReadyMessage(UUID player, String server, boolean ready, String detail)
        implements BackendToProxyMessage {

    public HandoffReadyMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(server, "server must not be null");
        Objects.requireNonNull(detail, "detail must not be null (use \"\" for no context)");
    }
}
