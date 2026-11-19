package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.paper.backend.BackendComposition;
import lol.candyriya.continuum.paper.integrations.metrics.BukkitMetricsService;
import lol.candyriya.continuum.paper.integrations.papi.PlaceholderSupport;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Paper/Folia entry point. Wiring lives elsewhere on purpose — this class
 * only starts and stops the service graph built in {@link PaperServices} and
 * {@link BackendComposition}.
 *
 * <p>The synchronization layer is not wired yet, so the plugin passes
 * {@link BackendComposition.Factories#vanilla()}: the join gate arms and
 * freezes players, then lifts immediately (nothing is pending restore). When
 * the storage/messaging integration lands, the one-line change is here — the
 * factories it provides are the only thing that changes.</p>
 */
public final class ContinuumPaperPlugin extends JavaPlugin {

    private PaperServices services;
    private PlaceholderSupport placeholders;

    @Override
    public void onEnable() {
        this.services = PaperServices.start(this, BackendComposition.Factories.vanilla());
        // metrics and placeholders are both inert without their hosts; neither can fail the enable
        services.metrics().register(BukkitMetricsService.createIfEnabled(
                this, getDataFolder().toPath().resolve("platform.yml"), services.config(), getSLF4JLogger()));
        this.placeholders = PlaceholderSupport.start(this, services.config(), getSLF4JLogger());
        getSLF4JLogger().info("continuum backend enabled ({})", getServer().getName());
    }

    @Override
    public void onDisable() {
        // unregister the expansion first; the services graph closes metrics via its slot
        if (placeholders != null) {
            placeholders.close();
            placeholders = null;
        }
        // shutdown lifts any held players, closes messaging, storage, channels, scheduler and timers
        if (services != null) {
            services.close();
            services = null;
        }
    }
}
