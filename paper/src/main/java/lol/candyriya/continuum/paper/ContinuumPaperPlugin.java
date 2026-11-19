package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.paper.backend.BackendComposition;

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

    @Override
    public void onEnable() {
        this.services = PaperServices.start(this, BackendComposition.Factories.vanilla());
        getSLF4JLogger().info("continuum backend enabled ({})", getServer().getName());
    }

    @Override
    public void onDisable() {
        // shutdown lifts any held players, closes messaging, storage, channels, scheduler and timers
        if (services != null) {
            services.close();
            services = null;
        }
    }
}
