package lol.candyriya.continuum.common;

import lol.candyriya.continuum.api.ContinuumApi;
import lol.candyriya.continuum.api.Continuums;
import lol.candyriya.continuum.common.config.ConfigManager;
import lol.candyriya.continuum.common.config.PluginConfig;
import lol.candyriya.continuum.common.feature.WelcomeService;
import lol.candyriya.continuum.common.message.MessageService;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The plugin core: all shared state and all the real logic.
 *
 * <p>Created once on platform startup ({@link #bootstrap(CoreContext)}), handed out to
 * commands and listeners, shut down on disable ({@link #shutdown()}). Implements
 * {@link ContinuumApi}, so third-party plugins see the same object through
 * {@link Continuums}.</p>
 *
 * <p>Async work runs on virtual threads (Project Loom): cheaper than platform pools for
 * short tasks, no sizing to tune. Long-running or ticking work belongs on the platform
 * scheduler, which survives reloads.</p>
 */
public final class ContinuumCore implements ContinuumApi {

    private final CoreContext context;
    private final ConfigManager configs;
    private final MessageService messages = new MessageService();
    private final WelcomeService welcomes;
    private final ExecutorService async;

    private ContinuumCore(CoreContext context, ConfigManager configs) {
        this.context = context;
        this.configs = configs;
        this.welcomes = new WelcomeService(messages);
        this.async = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("continuum-", 0).factory());
    }

    /** Creates the core: plugin folder, first config load, API registration. */
    public static @NotNull ContinuumCore bootstrap(@NotNull CoreContext context) {
        Objects.requireNonNull(context, "context");
        var configs = new ConfigManager(context.dataDirectory(), context.logger());
        configs.reload();
        var core = new ContinuumCore(context, configs);
        Continuums.register(core);
        context.logger().info("Core started, version {}", context.version());
        return core;
    }

    /** Current config, never null: falls back to defaults in the worst case. */
    public @NotNull PluginConfig config() {
        return configs.current();
    }

    public @NotNull MessageService messages() {
        return messages;
    }

    public @NotNull WelcomeService welcomes() {
        return welcomes;
    }

    public @NotNull Logger logger() {
        return context.logger();
    }

    /** Background work executor. Not for ticking or forever-blocking work — that is what the platform scheduler is for. */
    public @NotNull ExecutorService async() {
        return async;
    }

    /** Re-reads the config, true on success. */
    @Override
    public boolean reload() {
        boolean ok = configs.reload();
        if (config().debug()) {
            context.logger().info("Config reloaded: {}", config());
        }
        return ok;
    }

    /** Shuts the core down: thread pool and API unregistration. Strictly for disable time. */
    public void shutdown() {
        Continuums.unregister();
        async.close();
        context.logger().info("Core stopped");
    }

    // ContinuumApi: third-party code sees the same object as the platform does.

    @Override
    public @NotNull String version() {
        return context.version();
    }

    @Override
    public @NotNull Optional<Component> welcomeMessage(@NotNull String playerName) {
        return welcomes.welcomeMessage(config(), playerName);
    }
}
