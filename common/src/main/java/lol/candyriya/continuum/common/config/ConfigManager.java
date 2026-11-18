package lol.candyriya.continuum.common.config;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads and reloads config.yml.
 *
 * <p>The rules are simple and strict:</p>
 * <ul>
 *   <li>file missing — copy the default from the jar resources, never invent values;</li>
 *   <li>file broken — keep the previous config, log the reason, the plugin keeps running;</li>
 *   <li>file missing new keys — write them in with default values.</li>
 * </ul>
 * <p>The plugin never dies because of a config. The worst you get is a warn in the log.</p>
 */
public final class ConfigManager {

    private final Path file;
    private final Logger logger;
    private final YamlConfigurationLoader loader;

    private volatile PluginConfig config = PluginConfig.defaults();

    // ReentrantLock instead of synchronized on the method: reload does blocking file IO,
    // and callers may run it from virtual threads (Cloud command handlers are not loop threads)
    // — Lock parks the carrier thread fine, synchronized does not.
    private final ReentrantLock lock = new ReentrantLock();

    public ConfigManager(@NotNull Path dataDirectory, @NotNull Logger logger) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.file = dataDirectory.resolve("config.yml");
        this.loader = YamlConfigurationLoader.builder().path(file).build();
    }

    /** Current config. Volatile read: reload may come in from another thread. */
    public @NotNull PluginConfig current() {
        return config;
    }

    /**
     * Re-reads the file. Thread-safe: two concurrent /continuum reload calls
     * simply run one after another, the config cannot tear.
     *
     * @return true if the reload succeeded
     */
    public boolean reload() {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                copyDefault();
            }
            var node = loader.load();
            // Migrations first (by schema version; files from before versions existed count as v0),
            // then key-by-key reads with defaults. Order matters: a migration may rename
            // keys, and the healer below only backfills what is still missing.
            ConfigMigrations.migrations().apply(node);
            // Read key-by-key with defaults instead of mapping the whole record at once:
            // missing keys (including nested ones) pick up default values instead of null,
            // and unknown user keys stay in the file untouched.
            var def = PluginConfig.defaults();
            var welcomeNode = node.node("welcome");
            var welcomeDef = def.welcome();
            var loaded = new PluginConfig(
                    node.node("prefix").getString(def.prefix()),
                    node.node("debug").getBoolean(def.debug()),
                    new PluginConfig.Welcome(
                            welcomeNode.node("enabled").getBoolean(welcomeDef.enabled()),
                            welcomeNode.node("message").getString(welcomeDef.message())));
            // Self-healing: write back whatever was missing from the file.
            node.node("prefix").set(String.class, loaded.prefix());
            node.node("debug").set(Boolean.class, loaded.debug());
            welcomeNode.node("enabled").set(Boolean.class, loaded.welcome().enabled());
            welcomeNode.node("message").set(String.class, loaded.welcome().message());
            loader.save(node);
            config = loaded;
            return true;
        } catch (IOException e) {
            // ConfigurateException extends IOException, so one catch covers both broken YAML and disk errors.
            logger.warn("Failed to reload config.yml ({}), keeping the previous config", file, e);
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void copyDefault() throws IOException {
        try (InputStream in = ConfigManager.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                // No default in resources (broken build) — write a config from defaults() instead.
                var node = loader.createNode();
                node.set(PluginConfig.class, PluginConfig.defaults());
                loader.save(node);
                return;
            }
            Files.copy(in, file);
        } catch (ConfigurateException e) {
            throw new IOException("Failed to write the default config", e);
        }
    }
}
