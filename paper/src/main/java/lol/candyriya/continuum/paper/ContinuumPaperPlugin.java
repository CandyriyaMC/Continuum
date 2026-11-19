package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.paper.backend.BackendComposition;
import lol.candyriya.continuum.paper.integrations.metrics.BukkitMetricsService;
import lol.candyriya.continuum.paper.integrations.papi.PlaceholderSupport;
import lol.candyriya.continuum.paper.update.PaperUpdater;
import lol.candyriya.continuum.paper.orchestration.OrchestrationWiring;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Paper/Folia entry point. Wiring lives elsewhere on purpose — this class
 * only starts and stops the service graph built in {@link PaperServices} and
 * {@link BackendComposition}.
 *
 * <p>The synchronization layer is wired through {@link OrchestrationWiring}: on
 * enable, storage (SQL or MongoDB, picked from the shared config) and the
 * restore pipeline (join-gate handler, per-player FIFO, dirty tracking) are
 * composed into the backend graph; on disable, the shutdown capture runs
 * before the storage and gate go down. Passing
 * {@link BackendComposition.Factories#vanilla()} instead keeps the join gate
 * open with no synchronization — the escape hatch for diagnostics.</p>
 */
public final class ContinuumPaperPlugin extends JavaPlugin {

    private PaperServices services;
    private PlaceholderSupport placeholders;
    private PaperUpdater updater;
    private OrchestrationWiring wiring;

    @Override
    public void onEnable() {
        this.wiring = OrchestrationWiring.create(this);
        try {
            this.services = PaperServices.start(this, wiring.factories());
        } catch (RuntimeException e) {
            // the composition unwinds its own failures; release our wiring too
            wiring.close();
            wiring = null;
            throw e;
        }
        // metrics and placeholders are both inert without their hosts; neither can fail the enable
        services.metrics().register(BukkitMetricsService.createIfEnabled(
                this, getDataFolder().toPath().resolve("platform.yml"), services.config(), getSLF4JLogger()));
        this.placeholders = PlaceholderSupport.start(this, services.config(), getSLF4JLogger());
        try {
            this.updater = PaperUpdater.create(this, getSLF4JLogger(), getDataFolder().toPath());
            this.updater.start();
        } catch (Throwable t) {
            // the updater is an extra, never a prerequisite
            getSLF4JLogger().error("update service failed to start; continuum runs normally without it", t);
        }
        getSLF4JLogger().info("continuum backend enabled ({})", getServer().getName());
    }

    @Override
    public void onDisable() {
        // updater first (stop network + timers), placeholders next, then the
        // shutdown capture while storage is still open, then the normal teardown
        if (updater != null) {
            updater.close();
            updater = null;
        }
        if (placeholders != null) {
            placeholders.close();
            placeholders = null;
        }
        if (wiring != null) {
            wiring.close();
            wiring = null;
        }
        if (services != null) {
            services.close();
            services = null;
        }
    }
}
