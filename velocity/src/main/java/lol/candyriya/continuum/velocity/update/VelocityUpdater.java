package lol.candyriya.continuum.velocity.update;

import lol.candyriya.continuum.common.update.ClasspathPublicKeySource;
import lol.candyriya.continuum.common.update.JdkHttpTransport;
import lol.candyriya.continuum.common.update.ManifestVerifier;
import lol.candyriya.continuum.common.update.Staging;
import lol.candyriya.continuum.common.update.UpdatePhase;
import lol.candyriya.continuum.common.update.UpdateSettings;
import lol.candyriya.continuum.common.update.UpdateSettingsLoader;
import lol.candyriya.continuum.common.update.UpdateSnapshot;
import lol.candyriya.continuum.common.update.UpdateStateStore;
import lol.candyriya.continuum.common.update.Updater;
import lol.candyriya.continuum.velocity.BuildConstants;

import com.velocitypowered.api.network.ProtocolVersion;

import org.slf4j.Logger;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * The proxy's updater: owns the jar swap Velocity cannot do for itself, then
 * hands everything else to the shared engine.
 *
 * <p>Construction order matters and is deliberate: the rename swap runs <em>
 * before</em> a single config is parsed - a broken node must not block the
 * apply of an update that fixes it. The swap never loads anything, it only
 * moves files; the running JVM keeps executing the old build until the
 * operator restarts.</p>
 *
 * <p>No player traffic goes anywhere near this code path. The only network
 * calls are the update HTTP GETs on a fixed 6h cadence.</p>
 */
public final class VelocityUpdater implements AutoCloseable {

    private final Updater delegate;

    private VelocityUpdater(Updater delegate) {
        this.delegate = delegate;
    }

    /**
     * Performs the jar swap (if any) and wires the shared engine. The swap
     * decision is independent of the update configuration - even an
     * update-checks-disabled node must apply a pending stage.
     */
    public static VelocityUpdater create(Object plugin, Logger logger, Path dataDirectory) {
        Path pluginsDir = dataDirectory.toAbsolutePath().normalize().getParent();
        if (pluginsDir == null) {
            pluginsDir = dataDirectory;
        }
        Path currentJar = jarPath(plugin, logger);

        // 1) swap first, before a single config read
        JarSwap.swapIfStaged(dataDirectory, pluginsDir, currentJar, logger);

        // 2) settings (seeded on first run); config.yml license.token probe as fallback
        UpdateSettings settings = UpdateSettingsLoader.load(dataDirectory,
                dataDirectory.resolve("config.yml"), logger);

        String mc = ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion();
        int javaMajor = Runtime.version().feature();
        Updater engine = new Updater(logger, settings,
                new JdkHttpTransport(Updater.SERVICE_HOST, "continuum-updater/" + BuildConstants.VERSION,
                        settings.redirectAllowSuffixes()),
                new ManifestVerifier(new ClasspathPublicKeySource()),
                new Staging(dataDirectory, pluginsDir, currentJar, logger),
                new UpdateStateStore(dataDirectory, logger),
                BuildConstants.VERSION, mc == null ? "" : mc, javaMajor, false);
        return new VelocityUpdater(engine);
    }

    /** Arms the boot-time detection and the 6h check cycle. Never throws. */
    public void start() {
        delegate.start();
    }

    /** Command surface: enqueue a check. */
    public CompletableFuture<UpdateSnapshot> checkNow() {
        return delegate.checkNow();
    }

    /** Command surface: check + download + stage, bypassing consent (not compat). */
    public CompletableFuture<UpdateSnapshot> installNow() {
        return delegate.installNow();
    }

    /** Snapshot for status output; never blocks. */
    public UpdateSnapshot snapshot() {
        return delegate.snapshot();
    }

    public UpdatePhase phase() {
        return delegate.phase();
    }

    /** Stops the cycle and closes the transport. Idempotent. */
    @Override
    public void close() {
        delegate.close();
    }

    /** The jar file backing this plugin's classloader; null when running from exploded classes. */
    private static Path jarPath(Object plugin, Logger logger) {
        try {
            var uri = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(uri);
            if (!path.toString().endsWith(".jar")) {
                // dev runs from build output, not a jar - the swap has nothing to grab
                return null;
            }
            return path;
        } catch (URISyntaxException | RuntimeException e) {
            logger.warn("could not resolve the running jar path ({}); update staging will be skipped this boot", e.toString());
            return null;
        }
    }
}
