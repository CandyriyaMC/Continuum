package lol.candyriya.continuum.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import lol.candyriya.continuum.common.command.ContinuumCommands;
import lol.candyriya.continuum.common.ContinuumCore;
import lol.candyriya.continuum.common.CoreContext;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Entry point of the Velocity plugin. Creates the core, commands and listeners,
 * tears everything down when the proxy stops.
 */
@Plugin(
        id = "continuum",
        name = "Continuum",
        version = BuildConstants.VERSION,
        description = "Distributed player-state synchronization for Minecraft networks.",
        url = "https://github.com/CandyriyaMC/Continuum",
        authors = {"CandyriyaMC"}
)
public class ContinuumVelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ContinuumCore core;

    @Inject
    public ContinuumVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.core = ContinuumCore.bootstrap(new CoreContext(dataDirectory, logger, BuildConstants.VERSION));

        // Manager built by hand: we don't need a Guice module, an identity mapper
        // over the native CommandSource is enough.
        var container = proxy.getPluginManager().getPlugin("continuum").orElseThrow();
        var commands = new VelocityCommandManager<CommandSource>(
                container,
                proxy,
                ExecutionCoordinator.simpleCoordinator(),
                SenderMapper.identity());
        ContinuumCommands.register(commands, VelocitySender::new, core);

        // Example background task on the proxy scheduler. Everything long-running and
        // periodic goes here, not into the core pool: the scheduler survives reloads and
        // shuts down together with the proxy.
        proxy.getScheduler().buildTask(this,
                        () -> logger.debug("Core tick, config: debug={}", core.config().debug()))
                .delay(Duration.ofSeconds(5))
                .repeat(Duration.ofMinutes(1))
                .schedule();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (core != null) {
            core.shutdown();
            core = null;
        }
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        var player = event.getPlayer();
        core.welcomes().welcomeMessage(core.config(), player.getUsername())
                .ifPresent(player::sendMessage);
    }
}
