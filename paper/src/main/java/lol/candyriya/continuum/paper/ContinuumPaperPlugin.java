package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.common.ContinuumCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the Paper/Folia plugin. The heavy lifting is done in the bootstrapper
 * (core + commands); this class only wires listeners and scheduler tasks.
 */
public final class ContinuumPaperPlugin extends JavaPlugin implements Listener {

    private ContinuumCore core;

    @Override
    public void onEnable() {
        this.core = PaperServices.core();

        getServer().getPluginManager().registerEvents(this, this);

        // Example background task on the global scheduler: survives reload,
        // runs on the server, cancelled on disable.
        getServer().getGlobalRegionScheduler().runAtFixedRate(
                this,
                // Scheduler parameter unused — anonymous variable _ (Java 22+).
                _ -> getSLF4JLogger().debug("Core tick, config: debug={}", core.config().debug()),
                20L,
                20L * 60L);
    }

    @Override
    public void onDisable() {
        // The Paper scheduler cancels our tasks on shutdown itself.
        if (core != null) {
            core.shutdown();
            core = null;
        }
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        var player = event.getPlayer();
        // Player is an Audience: Components go out without legacy serialization.
        core.welcomes().welcomeMessage(core.config(), player.getName())
                .ifPresent(player::sendMessage);
    }
}
