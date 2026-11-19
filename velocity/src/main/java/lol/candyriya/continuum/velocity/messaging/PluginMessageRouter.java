package lol.candyriya.continuum.velocity.messaging;

import com.velocitypowered.api.proxy.ServerConnection;

/**
 * Hands off an incoming plugin message frame for processing.
 *
 * <p>The payload is opaque on purpose: the envelope codec (op codes, Jackson
 * envelope, validation) binds in a later iteration. The handler upstream has
 * already done what it must before calling this - the event was marked handled
 * and the frame came from a backend connection, not a client.</p>
 *
 * <p>Implementations must never throw; a router that blows up the event path
 * stalls the proxy for everyone.</p>
 */
public interface PluginMessageRouter {

    /**
     * Routes one frame from a backend connection.
     *
     * @param source  the backend connection the frame arrived on (never a client)
     * @param payload the raw frame bytes
     */
    void route(ServerConnection source, byte[] payload);

    /**
     * Health snapshot for the status command.
     *
     * @param enabled         whether the plugin-message transport is enabled in config
     * @param channel         the channel frames travel on
     * @param maxPayloadBytes the configured frame size cap
     */
    MessagingHealth health(boolean enabled, String channel, int maxPayloadBytes);
}
