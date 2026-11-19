package lol.candyriya.continuum.paper.messaging;

import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Set;

/**
 * Seam between Continuum and the platform plugin-messaging plumbing.
 *
 * <p>Owns every channel this plugin registers. The messaging integration
 * (framing, dispatch, codec) is plugged in later through the composition
 * function — this interface is the only thing it may touch. Nothing here
 * knows about envelope formats or transports.</p>
 */
public interface MessengerRegistrar extends AutoCloseable {

    /**
     * Registers the outgoing channel (backend → proxy). Idempotent.
     *
     * @throws IllegalArgumentException when the channel is not a valid lowercase namespaced key ({@code namespace:channel})
     */
    void registerOutgoing(String channel);

    /**
     * Registers an incoming listener for {@code channel}. Re-registering the same
     * listener for the same channel is a no-op; registering a different listener
     * for an already-held channel replaces the previous registration.
     *
     * @throws IllegalArgumentException when the channel is not a valid lowercase namespaced key
     */
    void registerIncoming(String channel, PluginMessageListener listener);

    /** Unregisters one incoming listener. Unknown registrations are ignored (logged). */
    void unregisterIncoming(String channel, PluginMessageListener listener);

    /** Unregisters every registration this registrar holds. */
    void unregisterAll();

    /** Outgoing channels currently registered by this registrar. */
    Set<String> outgoingChannels();

    /** Incoming channels currently registered by this registrar. */
    Set<String> incomingChannels();

    /** Unregisters everything. Idempotent; called on shutdown. */
    @Override
    void close();
}
