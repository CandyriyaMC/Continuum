package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;

/**
 * Backend reports its current load so the proxy can make informed routing
 * decisions. Sent unprompted, unthrottled by the protocol — backends are
 * expected to rate-limit themselves (a few per minute is plenty); the proxy
 * treats every report as best-effort hint, never as a guarantee.
 *
 * @param server      this backend's own name as registered on the proxy;
 *                    {@code ^[a-z0-9][a-z0-9_.-]{0,63}$}
 * @param playerCount players online right now; 0..{@value PluginMessageConstraints#MAX_PLAYER_COUNT},
 *                    and never above {@code maxPlayers}
 * @param maxPlayers  configured player capacity; 1..{@value PluginMessageConstraints#MAX_PLAYER_COUNT}
 * @param accepting   {@code false} when the backend is draining and must not
 *                    receive new handoffs even with free slots
 * @param detail      free-form context for operators; may be empty,
 *                    at most {@value PluginMessageConstraints#MAX_LOG_TEXT} characters,
 *                    no control characters
 */
public record BackendStatusMessage(String server, int playerCount, int maxPlayers, boolean accepting,
                                   String detail) implements BackendToProxyMessage {

    public BackendStatusMessage {
        Objects.requireNonNull(server, "server must not be null");
        Objects.requireNonNull(detail, "detail must not be null (use \"\" for no context)");
    }
}
