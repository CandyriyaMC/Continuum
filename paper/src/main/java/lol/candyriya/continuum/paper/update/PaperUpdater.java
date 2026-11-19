package lol.candyriya.continuum.paper.update;

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

import io.papermc.paper.ServerBuildInfo;

import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import net.kyori.adventure.key.Key;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * The backend-node updater.
 *
 * <p>Paper applies whatever lands in {@code plugins/update/} at boot, before
 * plugins load, so this class never performs a live swap - it downloads,
 * verifies and stages, and the platform does the rest. No commands, no
 * players, no chat: the backend works autonomously per the consent in
 * {@code update.yml} and says a single line in the console when an update is
 * available.</p>
 */
public final class PaperUpdater implements AutoCloseable {

    private final Updater delegate;

    private PaperUpdater(Updater delegate) {
        this.delegate = delegate;
    }

    /** Wires the shared engine against this node's paths and platform facts. */
    public static PaperUpdater create(JavaPlugin plugin, Logger logger, Path dataDirectory) {
        Path pluginsDir = dataDirectory.toAbsolutePath().normalize().getParent();
        if (pluginsDir == null) {
            pluginsDir = dataDirectory;
        }
        Path currentJar = jarPath(plugin, logger);

        UpdateSettings settings = UpdateSettingsLoader.load(dataDirectory,
                dataDirectory.resolve("config.yml"), logger);

        String version = plugin.getPluginMeta().getVersion();
        String mc = minecraftVersion();
        boolean folia = isFolia();
        int javaMajor = Runtime.version().feature();

        Updater engine = new Updater(logger, settings,
                new JdkHttpTransport(Updater.SERVICE_HOST, "continuum-updater/" + version,
                        settings.redirectAllowSuffixes()),
                new ManifestVerifier(new ClasspathPublicKeySource()),
                new Staging(dataDirectory, pluginsDir, currentJar, logger),
                new UpdateStateStore(dataDirectory, logger),
                version, mc == null ? "" : mc, javaMajor, folia);
        return new PaperUpdater(engine);
    }

    /** Arms the boot-time detection (applied-update proof) and the 6h cycle. Never throws. */
    public void start() {
        delegate.start();
    }

    /** Command surface parity for diagnostics; never used on the backend today. */
    public CompletableFuture<UpdateSnapshot> checkNow() {
        return delegate.checkNow();
    }

    public CompletableFuture<UpdateSnapshot> installNow() {
        return delegate.installNow();
    }

    public UpdateSnapshot snapshot() {
        return delegate.snapshot();
    }

    public UpdatePhase phase() {
        return delegate.phase();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /** The jar file backing this plugin's classloader; null when running from exploded classes. */
    private static Path jarPath(Object plugin, Logger logger) {
        try {
            var uri = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(uri);
            return path.toString().endsWith(".jar") ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            logger.warn("could not resolve the running jar path ({}); update staging will be skipped this boot", e.toString());
            return null;
        }
    }

    private static String minecraftVersion() {
        try {
            return ServerBuildInfo.buildInfo().minecraftVersionId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isFolia() {
        try {
            return ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc", "folia"));
        } catch (Throwable t) {
            return false;
        }
    }
}
