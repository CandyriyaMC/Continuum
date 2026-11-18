package lol.candyriya.continuum.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import lol.candyriya.continuum.common.command.ContinuumCommands;
import lol.candyriya.continuum.common.ContinuumCore;
import lol.candyriya.continuum.common.CoreContext;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;

/**
 * Paper plugin bootstrap. Runs before onEnable, so the core and commands are created
 * here: commands registered at this stage are available even in datapack functions.
 *
 * <p>The core goes into {@link PaperServices} — the bootstrapper and the plugin instances
 * are different objects, there is no other channel to pass it through.</p>
 */
public class ContinuumPaperBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final BootstrapContext context) {
        var logger = context.getLogger();
        var version = context.getConfiguration().getVersion();
        var core = ContinuumCore.bootstrap(new CoreContext(context.getDataDirectory(), logger, version));
        PaperServices.core(core);

        var commands = PaperCommandManager.builder()
                .executionCoordinator(ExecutionCoordinator.simpleCoordinator())
                .buildBootstrapped(context);
        ContinuumCommands.register(commands, PaperSender::new, core);
        PaperServices.commands(commands);

        logger.info("Commands registered at the bootstrap stage");
    }
}
