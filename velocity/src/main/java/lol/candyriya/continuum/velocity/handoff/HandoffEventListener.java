package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.slf4j.Logger;

import java.util.Objects;

/**
 * Adapts Velocity's event surface onto the coordinator.
 *
 * <p>Registered once at boot, after the plugin's own handlers, so the
 * {@link PluginMessageEvent} observer here always runs behind the one that
 * marks our frames handled. It never consumes a frame - non-handoff frames and
 * non-transfer connections pass through untouched.</p>
 * <ul>
 *     <li>{@code ServerPreConnectEvent} - allows the connect the coordinator
 *         started; denies anything sideways while a transfer is in flight.</li>
 *     <li>{@code ServerConnectedEvent} - arms the restore bookkeeping.</li>
 *     <li>{@code DisconnectEvent} - abandons whatever the transfer was doing.</li>
 *     <li>{@code KickedFromServerEvent} - cheap before restore, expensive after.</li>
 *     <li>{@code PluginMessageEvent} - the handoff handshake frames.</li>
 * </ul>
 */
public final class HandoffEventListener {

    private final HandoffCoordinator coordinator;
    private final Logger logger;

    public HandoffEventListener(HandoffCoordinator coordinator, Logger logger) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        try {
            if (coordinator.denySidewaysMove(event.getPlayer(), event.getOriginalServer())) {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        } catch (RuntimeException e) {
            // a routing hiccup must never wedge the connection pipeline shut
            logger.error("handoff pre-connect policy blew up for {}; leaving the connection unhandled",
                    event.getPlayer().getUniqueId(), e);
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        try {
            coordinator.onServerConnected(event.getPlayer(), event.getServer());
        } catch (RuntimeException e) {
            logger.error("handoff landing bookkeeping blew up for {}", event.getPlayer().getUniqueId(), e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        try {
            coordinator.onDisconnect(event.getPlayer());
        } catch (RuntimeException e) {
            logger.error("handoff disconnect cleanup blew up for {}", event.getPlayer().getUniqueId(), e);
        }
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        try {
            coordinator.onKickedFromServer(event.getPlayer(), event.getServer());
        } catch (RuntimeException e) {
            logger.error("handoff kick handling blew up for {}", event.getPlayer().getUniqueId(), e);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        try {
            if (!ContinuumChannels.MAIN.equals(event.getIdentifier())) {
                return;
            }
            if (!(event.getSource() instanceof ServerConnection source)) {
                // client-sent frames on our channel are not a backend voice
                return;
            }
            coordinator.handleBackendFrame(source, event.getData());
        } catch (RuntimeException e) {
            logger.error("handoff frame handling blew up", e);
        }
    }
}
