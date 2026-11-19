package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.velocity.command.ContinuumCommands;
import lol.candyriya.continuum.velocity.handoff.HandoffEventListener;
import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;
import lol.candyriya.continuum.velocity.session.KickContext;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPreShutdownEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;

import net.kyori.adventure.text.Component;

import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import com.google.inject.Inject;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Entry point of the Velocity plugin.
 *
 * <p>Boot is deliberately cheap: read and validate the configs, build the
 * translator, register the plugin message channel and the command tree. No
 * database, no broker, no network calls - those belong to later iterations and
 * must not stall the proxy's login flow. Shutdown goes through one bounded,
 * idempotent path shared by both shutdown events.</p>
 */
@Plugin(
        id = "continuum",
        name = "Continuum",
        version = BuildConstants.VERSION,
        description = "Distributed player-state synchronization for Minecraft networks.",
        url = "https://github.com/CandyriyaMC/Continuum",
        authors = {"CandyriyaMC"}
)
public final class ContinuumVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityServices services;

    @Inject
    public ContinuumVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // config errors fail the plugin's enable, loudly and on purpose
        VelocityServices graph = VelocityServices.create(proxy, this, logger, dataDirectory);
        this.services = graph;

        proxy.getChannelRegistrar().register(ContinuumChannels.MAIN);

        // cloud registers into the Velocity command manager; must happen before the first login
        var container = proxy.getPluginManager().getPlugin("continuum")
                .orElseThrow(() -> new IllegalStateException("plugin container 'continuum' missing after enable"));
        var commands = new VelocityCommandManager<CommandSource>(
                container,
                proxy,
                ExecutionCoordinator.simpleCoordinator(),
                SenderMapper.identity());
        ContinuumCommands.register(commands, graph, proxy, logger);
        // transfer handshake: our listener observes backend frames and drives the handoff lifecycle
        proxy.getEventManager().register(this, new HandoffEventListener(graph.handoffs(), logger));

        var config = graph.config();
        logger.info("Continuum {} ready: node={} network={} role={}",
                BuildConstants.VERSION, config.network().serverId(), config.network().id(),
                config.network().role().name().toLowerCase(java.util.Locale.ROOT));
    }

    @Subscribe
    public void onProxyPreShutdown(ProxyPreShutdownEvent event) {
        closeServices("pre-shutdown");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        closeServices("shutdown");
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (services == null) {
            return;
        }
        services.registry().playerConnected(event.getPlayer(), event.getServer());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (services == null) {
            return;
        }
        services.registry().playerDisconnected(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (services == null) {
            return;
        }
        Component reason = event.getServerKickReason().orElse(null);
        services.kickPolicy().reconcile(new KickContext(
                event.getPlayer().getUniqueId(),
                event.getServer().getServerInfo().getName(),
                reason,
                Instant.now()));
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (services == null || !ContinuumChannels.MAIN.equals(event.getIdentifier())) {
            return;
        }
        // our channel: never forward frames anywhere by default; we consume them
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof ServerConnection source)) {
            // a client can put anything on the wire; it is never a backend voice
            logger.debug("dropped plugin message on continuum:main from non-backend source ({})",
                    event.getSource().getClass().getSimpleName());
            return;
        }
        byte[] payload = event.getData();
        int maxPayload = services.config().messaging().pluginMessage().maxPayloadBytes();
        if (payload.length > maxPayload) {
            logger.warn("dropped oversized {}-byte frame from backend '{}': exceeds messaging.plugin-message.max-payload-bytes ({})",
                    payload.length, source.getServerInfo().getName(), maxPayload);
            return;
        }
        try {
            services.router().route(source, payload);
        } catch (RuntimeException e) {
            // the router contract says never; this is the seatbelt, not the plan
            logger.error("plugin message router failed on a frame from backend '{}'", source.getServerInfo().getName(), e);
        }
    }

    private void closeServices(String phase) {
        if (services != null) {
            logger.info("services graph close requested at {}", phase);
            services.close();
        }
    }
}
