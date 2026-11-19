package lol.candyriya.continuum.velocity.integrations.metrics;

import lol.candyriya.continuum.common.config.ContinuumConfig;

import com.velocitypowered.api.proxy.ProxyServer;

import org.bstats.charts.AdvancedPie;
import org.bstats.charts.SimplePie;
import org.bstats.velocity.Metrics;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Velocity side of Continuum's optional bStats metrics.
 *
 * <p>bStats is opt-out and anonymous: it reports the proxy count, version
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
 * activation procedure; the opt-out ({@code enabled: false} in metrics.yml,
 * or {@code plugins/bStats/config.txt} for every plugin) keeps working
 * exactly the same before and after activation.</p>
 *
 * <p><b>Construction.</b> Velocity's DI would build the bStats {@code Factory}
 * if the plugin entry point asked for it, but the plugin's constructor is
 * deliberately minimal — so this service builds the exporter itself, through
 * reflection on the factory's (private, {@code @Inject}) constructor. If any
 * part of that fails — library internals change, the host is absent — the
 * failure is logged and the proxy continues without metrics.</p>
 *
 * <p>Charts read the shared configuration through a supplier bound to the live
 * graph snapshot, so after {@code /continuum reload} the reported values
 * follow the fresh configuration.</p>
 */
public final class VelocityMetricsService implements AutoCloseable {

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

    private VelocityMetricsService(Logger logger, Metrics metrics) {
        this.logger = logger;
        this.metrics = metrics;
    }

    /**
     * Creates the service. Never throws; on any failure it logs and returns an
     * inert instance, so an absent or broken metrics host can never break the
     * proxy's enable.
     *
     * @param proxy          the proxy server
     * @param pluginInstance the annotated plugin instance (bStats locates the plugin container from it)
     * @param logger         proxy logger
     * @param dataDirectory  the plugin's data directory; also anchors the bStats config path
     * @param config         supplier of the shared configuration the charts describe (called on every poll)
     */
    public static VelocityMetricsService createIfEnabled(ProxyServer proxy,
                                                         Object pluginInstance,
                                                         Logger logger,
                                                         Path dataDirectory,
                                                         Supplier<ContinuumConfig> config) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(pluginInstance, "pluginInstance");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(config, "config");

        boolean enabled = ProxyMetricsToggle.metricsEnabled(dataDirectory, logger);
        return switch (MetricsGate.decide(enabled, BSTATS_PLUGIN_ID)) {
            case DISABLED_BY_CONFIG -> {
                logger.info("metrics disabled (enabled: false)");
                yield new VelocityMetricsService(logger, null);
            }
            case NOT_REGISTERED -> {
                logger.info("metrics disabled (not yet registered)");
                yield new VelocityMetricsService(logger, null);
            }
            case ENABLED -> start(proxy, pluginInstance, logger, dataDirectory, config);
        };
    }

    private static VelocityMetricsService start(ProxyServer proxy,
                                                Object pluginInstance,
                                                Logger logger,
                                                Path dataDirectory,
                                                Supplier<ContinuumConfig> config) {
        try {
            // the factory ctor is private + @Inject — Guice would build it for an @Inject site;
            // the plugin entry keeps its constructor minimal, so we build it ourselves
            var factoryCtor = Metrics.Factory.class.getDeclaredConstructor(ProxyServer.class, Logger.class, Path.class);
            factoryCtor.setAccessible(true);
            var factory = (Metrics.Factory) factoryCtor.newInstance(proxy, logger, dataDirectory);
            Metrics metrics = factory.make(pluginInstance, BSTATS_PLUGIN_ID);

            metrics.addCustomChart(new SimplePie("storage_backend", () -> MetricsChartValues.storageBackend(config.get())));
            metrics.addCustomChart(new SimplePie("restore_order", () -> MetricsChartValues.restoreOrder(config.get())));
            metrics.addCustomChart(new AdvancedPie("modules_enabled", () -> MetricsChartValues.modulesEnabled(config.get())));
            logger.info("metrics enabled (bStats, anonymous; opt out in metrics.yml or plugins/bStats/config.txt)");
            return new VelocityMetricsService(logger, metrics);
        } catch (Throwable e) {
            // metrics must never take the proxy down
            logger.warn("could not start bStats metrics; continuing without them", e);
            return new VelocityMetricsService(logger, null);
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
            // shutdown must not break proxy shutdown
            logger.warn("metrics shutdown failed; continuing", e);
        }
    }
}
