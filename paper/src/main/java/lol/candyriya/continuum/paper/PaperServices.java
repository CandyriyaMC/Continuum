package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.paper.backend.BackendComposition;
import lol.candyriya.continuum.paper.config.PlatformConfig;
import lol.candyriya.continuum.paper.gate.RestoreGate;
import lol.candyriya.continuum.paper.messaging.MessengerRegistrar;
import lol.candyriya.continuum.paper.metrics.MetricsSlot;
import lol.candyriya.continuum.paper.scheduler.RegionizedScheduler;

import net.kyori.adventure.text.Component;

import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * The backend service graph. One instance per plugin lifetime, created by
 * {@link #start} in {@code onEnable}, closed by {@link #close()} in
 * {@code onDisable}.
 *
 * <p>Wiring order is fixed and deliberate (shared with {@link BackendComposition}):</p>
 * <ol>
 *   <li>bootstrap stage: {@code ContinuumConfig} (config.yml in the plugin's data directory,
 *       seeded if missing, fully validated), {@code PlatformConfig} (platform.yml, seeded if missing),
 *       and the {@link Translator} for the bundled catalogs — assembled by the bootstrapper
 *       and handed over via the static handoff</li>
 *   <li>messages ({@link PaperMessages}) — built from the translator and i18n config; the
 *       translator itself is exposed via {@link #translator()} for diagnostics and later modules</li>
 *   <li>platform scheduler — brand detection, one named implementation per platform</li>
 *   <li>gate-timer executor — bounded, daemon, named {@code continuum-gate-timer-*}</li>
 *   <li>messenger registrar — outgoing plugin-message channel armed when enabled</li>
 *   <li>spawn policy (from platform.yml)</li>
 *   <li>storage lifecycle (factory) — probed by the gate</li>
 *   <li>join gate — restore handler installed, listeners armed</li>
 *   <li>messaging integration (factory) — after the gate, so handlers can rely on it</li>
 *   <li>metrics slot — reserved, empty in this build; the metrics integration registers
 *       here exactly once</li>
 * </ol>
 *
 * <p>The bootstrapper and the plugin are two different objects Paper creates for the
 * same plugin; the bootstrap-to-enable handoff travels through the static
 * {@link Handoff} record — the only static state in the backend, set once by the
 * bootstrapper, consumed once by the plugin.</p>
 */
public final class PaperServices implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaperServices.class);

    private static volatile Handoff handoff;

    /** Bootstrap-stage output, handed from the bootstrapper to the plugin. */
    public record Handoff(ContinuumConfig config, PlatformConfig platform, Translator translator) {
    }

    private final JavaPlugin plugin;
    private final ContinuumConfig config;
    private final PlatformConfig platform;
    private final Translator translator;
    private final BackendComposition.Composed composed;
    private final MetricsSlot metrics = new MetricsSlot();

    private PaperServices(JavaPlugin plugin,
                          ContinuumConfig config,
                          PlatformConfig platform,
                          Translator translator,
                          BackendComposition.Composed composed) {
        this.plugin = plugin;
        this.config = config;
        this.platform = platform;
        this.translator = Objects.requireNonNull(translator, "translator");
        this.composed = composed;
    }

    /** Called by the bootstrapper exactly once, before the plugin object exists. Replaces any previous handoff. */
    public static void stash(Handoff value) {
        handoff = Objects.requireNonNull(value, "handoff");
    }

    /** The handoff deposited by the bootstrapper; consumed exactly once. Null when the bootstrapper never ran. */
    public static @Nullable Handoff consumeHandoff() {
        Handoff current = handoff;
        handoff = null;
        return current;
    }

    /**
     * Builds the service graph for {@code plugin} using the bootstrap handoff.
     *
     * @param plugin    the enabling plugin (registers listeners and messaging channels)
     * @param factories the integration factories; pass {@link BackendComposition.Factories#vanilla()} when no integration is wired
     * @throws IllegalStateException when the bootstrapper did not run (missing handoff)
     * @throws BackendComposition.CompositionException when a composition step fails; everything built so far is already closed
     */
    public static PaperServices start(JavaPlugin plugin, BackendComposition.Factories factories) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(factories, "factories");
        Handoff stashed = consumeHandoff();
        if (stashed == null) {
            throw new IllegalStateException("no backend handoff from the bootstrapper — did ContinuumPaperBootstrap run? "
                    + "(paper-plugin.yml bootstrapper = lol.candyriya.continuum.paper.ContinuumPaperBootstrap)");
        }
        BackendComposition.Composed composed = BackendComposition.compose(
                plugin, stashed.config(), stashed.platform(), stashed.translator(), factories);
        LOGGER.info("continuum backend composed for network '{}', node '{}'",
                stashed.config().network().id(), stashed.config().network().serverId());
        return new PaperServices(plugin, stashed.config(), stashed.platform(), stashed.translator(), composed);
    }

    /** The shared configuration (config.yml in the plugin's data directory). */
    public ContinuumConfig config() {
        return config;
    }

    /** The backend-node platform configuration (platform.yml in the plugin's data directory). */
    public PlatformConfig platform() {
        return platform;
    }

    /** The bundled-catalog translator the backend messages render against. Fallback chain: requested → configured fallback → en_US. */
    public Translator translator() {
        return translator;
    }

    /** Messages for this backend (locale chain is applied per call). */
    public PaperMessages messages() {
        return composed.messages();
    }

    /** The platform scheduler flavour this server runs. */
    public RegionizedScheduler scheduler() {
        return composed.scheduler();
    }

    /** The join gate — the only place player holds are lifted. */
    public RestoreGate gate() {
        return composed.gate();
    }

    /** The plugin-message messenger registrar. */
    public MessengerRegistrar messenger() {
        return composed.registrar();
    }

    /** Reserved slot for the metrics integration; see {@link MetricsSlot}. Nothing registers in this build. */
    public MetricsSlot metrics() {
        return metrics;
    }

    /** Renders {@code key} with the configured default locale. Convenience for admin-facing output. */
    public Component render(String key) {
        return composed.messages().render(key);
    }

    /**
     * Closes the service graph in reverse wiring order: messaging, gate (lifting any
     * held players), storage, channels, scheduler, timers. Never throws; every step
     * is isolated and logged.
     */
    @Override
    public void close() {
        metrics.close();
        composed.close();
        LOGGER.info("continuum backend closed ({})", plugin.getName());
    }
}
