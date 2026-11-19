package lol.candyriya.continuum.paper.integrations.metrics;

import lol.candyriya.continuum.common.config.ContinuumConfig;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;

import org.bukkit.plugin.java.JavaPlugin;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The Paper/Folia side of Continuum's optional bStats metrics.
 *
 * <p>bStats is opt-out and anonymous: it reports the server count, version
 * distribution and the charts registered below — all of which are fixed
 * category values derived from the configuration (storage engine, restore
 * order, module toggles). No player names, UUIDs, IPs, chat, locations or any
 * other personal data are ever collected, on any path.</p>
 *
 * <p><b>Activation story.</b> The bStats service id is written by hand into
 * {@link #BSTATS_PLUGIN_ID} when the plugin is registered on bstats.org at
 * first release — the id only exists after that wizard run, so it cannot be
 * inferred from this repository. Until the constant is a positive number,
 * this class registers nothing and opens no sockets: {@code createIfEnabled}
 * logs {@code "metrics disabled (not yet registered)"} at INFO and returns an
 * inert instance. Flipping the constant to the registered id is the entire
 * activation procedure; the opt-out ({@code metrics.enabled: false} in
 * platform.yml, or {@code plugins/bStats/config.yml} for every plugin) keeps
 * working exactly the same before and after activation.</p>
 *
 * <p>The host (bStats, shaded and relocated) is the only dependency added by
 * this integration. If constructing it fails for any reason, the failure is
 * logged and the plugin continues without metrics.</p>
 */
public final class BukkitMetricsService implements AutoCloseable {

    /**
     * The bStats service id assigned when the plugin is registered on
     * bstats.org. Zero means "not registered yet": the metrics integration is
     * inert and no network traffic of any kind leaves the process. Set this to
     * the id from the bStats wizard at first release; nothing else in the code
     * needs to change.
     */
    public static final int BSTATS_PLUGIN_ID = 0;

    private final Logger logger;
    private final @Nullable Metrics metrics;

    private BukkitMetricsService(Logger logger, @Nullable Metrics metrics) {
        this.logger = logger;
        this.metrics = metrics;
    }

    /**
     * Creates the service. Never throws; on any failure it logs and returns an
     * inert instance, so an absent or broken metrics host can never break
     * plugin enable.
     *
     * @param plugin        the enabling plugin (bStats needs it for scheduling and the descriptor)
     * @param platformConfig path to {@code platform.yml}; the toggle is read from it, defaulting to enabled
     * @param config         the shared configuration the charts describe
     * @param logger         plugin logger
     */
    public static BukkitMetricsService createIfEnabled(JavaPlugin plugin,
                                                       Path platformConfig,
                                                       ContinuumConfig config,
                                                       Logger logger) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(platformConfig, "platformConfig");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(logger, "logger");

        boolean enabled = PlatformMetricsToggle.metricsEnabled(platformConfig);
        return switch (MetricsGate.decide(enabled, BSTATS_PLUGIN_ID)) {
            case DISABLED_BY_CONFIG -> {
                logger.info("metrics disabled (metrics.enabled: false)");
                yield new BukkitMetricsService(logger, null);
            }
            case NOT_REGISTERED -> {
                logger.info("metrics disabled (not yet registered)");
                yield new BukkitMetricsService(logger, null);
            }
            case ENABLED -> start(plugin, config, logger);
        };
    }

    private static BukkitMetricsService start(JavaPlugin plugin, ContinuumConfig config, Logger logger) {
        try {
            Metrics metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("storage_backend", () -> MetricsChartValues.storageBackend(config)));
            metrics.addCustomChart(new SimplePie("restore_order", () -> MetricsChartValues.restoreOrder(config)));
            metrics.addCustomChart(new AdvancedPie("modules_enabled", () -> MetricsChartValues.modulesEnabled(config)));
            logger.info("metrics enabled (bStats, anonymous; opt out in platform.yml or plugins/bStats/config.yml)");
            return new BukkitMetricsService(logger, metrics);
        } catch (Throwable e) {
            // metrics must never take the backend down
            logger.warn("could not start bStats metrics; continuing without them", e);
            return new BukkitMetricsService(logger, null);
        }
    }

    /** True when the bStats exporter is actually registered and reporting. */
    public boolean isActive() {
        return metrics != null;
    }

    @Override
    public void close() {
        if (metrics == null) {
            return;
        }
        try {
            metrics.shutdown();
        } catch (Throwable e) {
            // shutdown must not break plugin disable
            logger.warn("metrics shutdown failed; continuing", e);
        }
    }
}
