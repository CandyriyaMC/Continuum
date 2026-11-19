package lol.candyriya.continuum.paper.messaging;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.messaging.PluginMessageListenerRegistration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link MessengerRegistrar} backed by the platform messenger.
 *
 * <p>Channel names must be valid lowercase namespaced keys (the same charset
 * the shared configuration enforces). Every registration is tracked so
 * {@link #close()} can tear the whole lot down without the caller remembering
 * what was registered when.</p>
 */
public final class BukkitMessengerRegistrar implements MessengerRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(BukkitMessengerRegistrar.class);

    /** lowercase namespace:path, same shape as messaging.plugin-message.channel in config.yml */
    private static final Pattern CHANNEL = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private final Plugin plugin;
    private final Messenger messenger;
    private final Set<String> outgoing = new LinkedHashSet<>();
    private final Map<String, Set<PluginMessageListener>> incoming = new LinkedHashMap<>();

    public BukkitMessengerRegistrar(Plugin plugin) {
        this.plugin = plugin;
        this.messenger = plugin.getServer().getMessenger();
    }

    @Override
    public synchronized void registerOutgoing(String channel) {
        checkChannel(channel);
        if (!outgoing.add(channel)) {
            return; // already held
        }
        messenger.registerOutgoingPluginChannel(plugin, channel);
        LOGGER.debug("registered outgoing plugin message channel {}", channel);
    }

    @Override
    public synchronized void registerIncoming(String channel, PluginMessageListener listener) {
        checkChannel(channel);
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        var listeners = incoming.computeIfAbsent(channel, _ -> new LinkedHashSet<>());
        if (listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        PluginMessageListenerRegistration registration = messenger.registerIncomingPluginChannel(plugin, channel, listener);
        if (!messenger.isRegistrationValid(registration)) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                incoming.remove(channel);
            }
            throw new IllegalStateException("incoming registration for channel " + channel + " was rejected by the platform");
        }
        LOGGER.debug("registered incoming plugin message channel {}", channel);
    }

    @Override
    public synchronized void unregisterIncoming(String channel, PluginMessageListener listener) {
        var listeners = incoming.get(channel);
        if (listeners == null || !listeners.remove(listener)) {
            LOGGER.debug("incoming listener for channel {} was not registered; nothing to unregister", channel);
            return;
        }
        messenger.unregisterIncomingPluginChannel(plugin, channel, listener);
        if (listeners.isEmpty()) {
            incoming.remove(channel);
        }
    }

    @Override
    public synchronized void unregisterAll() {
        for (var entry : incoming.entrySet()) {
            for (PluginMessageListener listener : entry.getValue()) {
                try {
                    messenger.unregisterIncomingPluginChannel(plugin, entry.getKey(), listener);
                } catch (RuntimeException e) {
                    LOGGER.warn("failed to unregister incoming channel {}", entry.getKey(), e);
                }
            }
        }
        incoming.clear();
        for (String channel : outgoing) {
            try {
                messenger.unregisterOutgoingPluginChannel(plugin, channel);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to unregister outgoing channel {}", channel, e);
            }
        }
        outgoing.clear();
    }

    @Override
    public synchronized Set<String> outgoingChannels() {
        return Set.copyOf(outgoing);
    }

    @Override
    public synchronized Set<String> incomingChannels() {
        return Set.copyOf(incoming.keySet());
    }

    @Override
    public void close() {
        unregisterAll();
    }

    private static void checkChannel(String channel) {
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw new IllegalArgumentException("channel must be a lowercase namespaced key like 'continuum:messaging', got: " + channel);
        }
    }
}
