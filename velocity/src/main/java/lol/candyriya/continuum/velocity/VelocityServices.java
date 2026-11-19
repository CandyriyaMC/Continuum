package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.ConfigLoader;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.velocity.integrations.metrics.VelocityMetricsService;
import lol.candyriya.continuum.velocity.license.LicenseService;
import lol.candyriya.continuum.velocity.license.NotConfiguredLicenseService;
import lol.candyriya.continuum.velocity.messaging.BackendMessenger;
import lol.candyriya.continuum.velocity.messaging.DefaultPluginMessageRouter;
import lol.candyriya.continuum.velocity.messaging.PluginMessageRouter;
import lol.candyriya.continuum.velocity.session.KickDecisionPolicy;
import lol.candyriya.continuum.velocity.session.NetworkSessionRegistry;
import lol.candyriya.continuum.velocity.session.NotifyAndKeepWaitPolicy;
import lol.candyriya.continuum.velocity.session.SessionTokenSource;
import lol.candyriya.continuum.velocity.session.UnavailableSessionTokenSource;

import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The services graph: everything the proxy wires at boot, in one place.
 *
 * <p>Construction order at boot: shared config (seed + load + validate), then
 * proxy-local config, then the merged translator, then the stateless-ish
 * services (messenger, registry, license, tokens), then the executors used for
 * teardown. Nothing heavy happens on the init path beyond reading files.</p>
 *
 * <p>{@code reload()} re-reads and re-validates everything reloadable and swaps
 * it in one shot; on any failure the previous graph stays active. The hot
 * values (config, locales) are read through a single volatile {@link GraphSnapshot}
 * reference, so no call site ever observes a half-new graph.</p>
 *
 * <p>Metrics are a slot on purpose: the internal {@link Metrics} seam the hot paths
 * write to stays NOOP until a real provider is wired in. The optional bStats
 * exporter lives in {@code integrations.metrics} and is wired here via explicit
 * construction — it is inert until the service id is set at first release, and
 * always opt-out.</p>
 */
public final class VelocityServices implements AutoCloseable {

    /** Upper bound for the whole teardown, shared by both shutdown hooks. */
    public static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /** Config + locales, swapped atomically on reload. */
    private record GraphSnapshot(ContinuumConfig config, VelocityLocalConfig localConfig, Translator translator) {
    }

    private final Logger logger;
    private final Path dataDirectory;
    private final ClassLoader pluginClassLoader;
    private final Path externalLocalesDir;

    private final AtomicReference<GraphSnapshot> snapshot;
    private final VelocityScheduler scheduler;
    private final BackendMessenger messenger;
    private final PluginMessageRouter router;
    private final NetworkSessionRegistry registry;
    private final SessionTokenSource tokenSource;
    private final Metrics metrics;
    private final VelocityMetricsService metricsService;
    private final LicenseService licenseService;
    private final KickDecisionPolicy kickPolicy;
    private final VelocityMessages messages;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService closePool;

    private VelocityServices(Logger logger,
                             Path dataDirectory,
                             ClassLoader pluginClassLoader,
                             Path externalLocalesDir,
                             AtomicReference<GraphSnapshot> snapshot,
                             VelocityScheduler scheduler,
                             BackendMessenger messenger,
                             PluginMessageRouter router,
                             NetworkSessionRegistry registry,
                             SessionTokenSource tokenSource,
                             Metrics metrics,
                             VelocityMetricsService metricsService,
                             LicenseService licenseService,
                             KickDecisionPolicy kickPolicy,
                             VelocityMessages messages) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.pluginClassLoader = pluginClassLoader;
        this.externalLocalesDir = externalLocalesDir;
        this.snapshot = snapshot;
        this.scheduler = scheduler;
        this.messenger = messenger;
        this.router = router;
        this.registry = registry;
        this.tokenSource = tokenSource;
        this.metrics = metrics;
        this.metricsService = metricsService;
        this.licenseService = licenseService;
        this.kickPolicy = kickPolicy;
        this.messages = messages;
        this.closePool = Executors.newFixedThreadPool(1,
                Thread.ofPlatform().name("continuum-services-close-", 0).daemon(true).factory());
    }

    /**
     * Seeds, loads and wires the whole graph. Throws (failing the plugin's
     * enable) when the configs are invalid - a proxy guessed into service is a
     * proxy mis-routed.
     */
    public static VelocityServices create(ProxyServer proxy, Object plugin, Logger logger, Path dataDirectory) {
        Path configPath = dataDirectory.resolve("config.yml");
        ConfigLoader.seedIfMissing(configPath);
        ContinuumConfig config = ConfigLoader.load(configPath);

        Path localPath = dataDirectory.resolve(VelocityLocalConfigLoader.FILE_NAME);
        VelocityLocalConfigLoader.seedIfMissing(localPath);
        VelocityLocalConfig localConfig = VelocityLocalConfigLoader.load(localPath);

        ClassLoader classLoader = plugin.getClass().getClassLoader();
        Path localesDir = dataDirectory.resolve("locales");
        Translator translator = VelocityCatalogs.build(classLoader, config.i18n(), localesDir);

        AtomicReference<GraphSnapshot> snapshot = new AtomicReference<>(new GraphSnapshot(config, localConfig, translator));
        Metrics metrics = Metrics.NOOP;
        // metrics service is self-detecting and self-seeding; inert until the bStats id is set at first release
        VelocityMetricsService metricsService = VelocityMetricsService.createIfEnabled(
                proxy, plugin, logger, dataDirectory, () -> snapshot.get().config());
        VelocityScheduler scheduler = new VelocityScheduler(proxy, plugin);
        BackendMessenger messenger = new BackendMessenger(logger, metrics,
                () -> snapshot.get().config().messaging().pluginMessage().maxPayloadBytes());
        DefaultPluginMessageRouter router = new DefaultPluginMessageRouter(logger, metrics);
        NetworkSessionRegistry registry = new NetworkSessionRegistry(logger, metrics, localConfig.reconnectDebounceSeconds());
        SessionTokenSource tokenSource = new UnavailableSessionTokenSource();
        LicenseService licenseService = new NotConfiguredLicenseService();
        KickDecisionPolicy kickPolicy = new NotifyAndKeepWaitPolicy(registry, logger);
        PlayerLocaleResolver localeResolver = new PlayerLocaleResolver(PlayerLocaleSource.none(), logger);
        VelocityMessages messages = new VelocityMessages(
                () -> snapshot.get().translator(),
                () -> snapshot.get().config().i18n(),
                localeResolver);

        return new VelocityServices(logger, dataDirectory, classLoader, localesDir, snapshot,
                scheduler, messenger, router, registry, tokenSource, metrics, metricsService,
                licenseService, kickPolicy, messages);
    }

    /**
     * Re-reads and re-validates the shared config, the proxy-local config and
     * the locales, then swaps them in atomically.
     *
     * @return true when the reload was applied, false when it was rejected (previous graph kept)
     */
    public boolean reload() {
        Path configPath = dataDirectory.resolve("config.yml");
        Path localPath = dataDirectory.resolve(VelocityLocalConfigLoader.FILE_NAME);
        try {
            ConfigLoader.seedIfMissing(configPath);
            VelocityLocalConfigLoader.seedIfMissing(localPath);
            ContinuumConfig newConfig = ConfigLoader.load(configPath);
            VelocityLocalConfig newLocal = VelocityLocalConfigLoader.load(localPath);
            Translator newTranslator = VelocityCatalogs.build(pluginClassLoader, newConfig.i18n(), externalLocalesDir);

            snapshot.set(new GraphSnapshot(newConfig, newLocal, newTranslator));
            registry.setReconnectDebounceSeconds(newLocal.reconnectDebounceSeconds());
            logger.info("reload complete: configuration re-read and locales rebuilt");
            return true;
        } catch (Exception e) {
            logger.error("reload failed; keeping the previous configuration and locales", e);
            return false;
        }
    }

    public ContinuumConfig config() {
        return snapshot.get().config();
    }

    public VelocityLocalConfig localConfig() {
        return snapshot.get().localConfig();
    }

    public VelocityMessages messages() {
        return messages;
    }

    public VelocityScheduler scheduler() {
        return scheduler;
    }

    public BackendMessenger messenger() {
        return messenger;
    }

    public PluginMessageRouter router() {
        return router;
    }

    public NetworkSessionRegistry registry() {
        return registry;
    }

    public SessionTokenSource tokenSource() {
        return tokenSource;
    }

    public Metrics metrics() {
        return metrics;
    }

    public LicenseService licenseService() {
        return licenseService;
    }

    public KickDecisionPolicy kickPolicy() {
        return kickPolicy;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    /**
     * Tears the graph down with a hard timeout. Safe to call twice; the second
     * call is a no-op. Failures are logged, never thrown - the proxy is going
     * down anyway and half-closed is not made worse by ceremony.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Future<?> teardown = closePool.submit(this::teardownGraph);
        try {
            teardown.get(DEFAULT_CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            logger.info("services graph closed");
        } catch (TimeoutException e) {
            logger.error("services graph did not close within {}s; continuing shutdown", DEFAULT_CLOSE_TIMEOUT.toSeconds());
            teardown.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("interrupted while closing the services graph", e);
        } catch (ExecutionException e) {
            logger.error("services graph teardown failed", e.getCause());
        } finally {
            closePool.shutdownNow();
        }
    }

    private void teardownGraph() {
        List<String> failures = new ArrayList<>();
        closeStep("metrics", metricsService::close, failures);
        closeStep("session registry", registry::close, failures);
        // storage and broker closeables plug in here when they are wired
        if (!failures.isEmpty()) {
            logger.error("teardown reported {} failure(s): {}", failures.size(), String.join("; ", failures));
        }
    }

    private void closeStep(String name, AutoCloseable closeable, List<String> failures) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.error("failed to close {}", name, e);
            failures.add(name);
        }
    }
}
