package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Target backend reports that the player's session was restored and resumed
 * on this backend — the confirm step of a handoff. The proxy marks the
 * transfer committed when it sees this.
 *
 * <p>This is a terminal report: it carries no reply. Backends send it once,
 * after restore finishes; anything failed earlier in the flow surfaces as
 * the absence of this message (the proxy's transfer machinery times the
 * operation out on its own).</p>
 *
 * @param player uuid of the player whose session resumed here; must not be nil
 * @param server this backend's own name as registered on the proxy;
 *               {@code ^[a-z0-9][a-z0-9_.-]{0,63}$}
 * @param detail free-form context for logs and operators; may be empty,
 *               at most {@value PluginMessageConstraints#MAX_LOG_TEXT} characters,
 *               no control characters
 */
public record HandoffResumedMessage(UUID player, String server, String detail)
        implements BackendToProxyMessage {

    public HandoffResumedMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(server, "server must not be null");
        Objects.requireNonNull(detail, "detail must not be null (use \"\" for no context)");
    }
}
