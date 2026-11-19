package lol.candyriya.continuum.paper.backend;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.paper.PaperMessages;
import lol.candyriya.continuum.paper.config.PlatformConfig;
import lol.candyriya.continuum.paper.gate.LastKnownLocationProvider;
import lol.candyriya.continuum.paper.gate.RestoreGate;
import lol.candyriya.continuum.paper.gate.SpawnPolicies;
import lol.candyriya.continuum.paper.gate.SpawnPolicy;
import lol.candyriya.continuum.paper.messaging.BukkitMessengerRegistrar;
import lol.candyriya.continuum.paper.messaging.MessengerRegistrar;
import lol.candyriya.continuum.paper.scheduler.RegionizedScheduler;
import lol.candyriya.continuum.paper.scheduler.SchedulerFactory;

import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one place where the backend is wired together.
 *
 * <p>Everything the synchronization layer will ever need is passed in as
 * factory callables via {@link Factories}; everything the backend itself owns
 * is created here in a fixed order and torn down here in reverse. Integration
 * lands by changing the {@code Factories} this function is called with — one
 * call site in the plugin — and nowhere else.</p>
 *
 * <p>Wiring order (documented, do not reorder):</p>
 * <ol>
 *   <li>messages (translator + locale chain)</li>
 *   <li>platform scheduler (brand detection)</li>
 *   <li>bounded gate-timer executor (timeouts + failure-map reaper)</li>
 *   <li>messenger registrar + outgoing plugin-message channel (when enabled)</li>
 *   <li>spawn policy (from platform.yml)</li>
 *   <li>storage lifecycle (factory) — probed by the gate</li>
 *   <li>join gate, restore handler installed, listeners armed</li>
 *   <li>messaging integration (factory) — after the gate, so handlers can rely on it</li>
 * </ol>
 *
 * <p>Any failure unwinds everything built so far and throws
 * {@link CompositionException} naming the step — the plugin then fails to
 * enable cleanly instead of running half-wired.</p>
 */
public final class BackendComposition {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackendComposition.class);

    private BackendComposition() {
    }

    /**
     * The integration factories. All optional; the vanilla plugin passes
     * {@link #vanilla()} — storage, messaging and restore all stay unwired and the
     * join gate lifts joins immediately instead of holding players.
     */
    public record Factories(@Nullable StorageLifecycleFactory storage,
                            @Nullable MessagingInstaller messaging,
                            @Nullable RestoreHandlerFactory restore,
                            @Nullable LastKnownLocationProvider lastKnownLocation) {

        /** No integrations: everything optional stays off. */
        public static Factories vanilla() {
            return new Factories(null, null, null, null);
        }
    }

    /** Everything the composition produced, in wiring order. Closed in reverse by {@link #close()}. */
    public record Composed(PaperMessages messages,
                           RegionizedScheduler scheduler,
                           SpawnPolicy spawnPolicy,
                           MessengerRegistrar registrar,
                           RestoreGate gate,
                           @Nullable StorageLifecycle storage,
                           @Nullable MessagingRegistration messaging,
                           ScheduledExecutorService gateTimer) implements AutoCloseable {

        /** Tears down everything in reverse wiring order. Never throws; logs each failure and continues. */
        @Override
        public void close() {
            closeStep("messaging", messaging);
            closeStep("gate", gate);
            closeStep("storage", storage);
            closeStep("registrar", registrar);
            closeStep("scheduler", scheduler);
            gateTimer.shutdownNow();
            try {
                if (!gateTimer.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOGGER.warn("gate timer did not terminate within 2s of shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private static void closeStep(String step, @Nullable AutoCloseable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warn("closing backend step '{}' failed; continuing shutdown", step, e);
            }
        }
    }

    /** Composes the backend. On any failure, everything built so far is closed and a {@link CompositionException} is thrown. */
    public static Composed compose(Plugin plugin,
                                   ContinuumConfig config,
                                   PlatformConfig platform,
                                   Translator translator,
                                   Factories factories) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(translator, "translator");
        Objects.requireNonNull(factories, "factories");

        // 1. messages — first, everything user-visible below may need them
        PaperMessages messages = new PaperMessages(translator, config.i18n());

        // 2. platform scheduler — brand detection, fails hard on a non-Paper/Folia server
        RegionizedScheduler scheduler;
        try {
            scheduler = SchedulerFactory.detect(plugin);
        } catch (RuntimeException e) {
            throw new CompositionException("scheduler", "could not detect the server platform", e);
        }

        // 3. bounded gate-timer executor — named, daemon, two threads
        ScheduledExecutorService gateTimer = newGateTimer();

        // 4. messenger registrar + outgoing channel — before anything can announce
        MessengerRegistrar registrar = new BukkitMessengerRegistrar(plugin);
        if (config.messaging().pluginMessage().enabled()) {
            try {
                registrar.registerOutgoing(config.messaging().pluginMessage().channel());
            } catch (RuntimeException e) {
                gateTimer.shutdownNow();
                closeQuietly(scheduler);
                throw new CompositionException("messaging", "could not register the outgoing plugin message channel", e);
            }
        } else {
            LOGGER.info("plugin messaging disabled in config; outgoing channel not registered");
        }

        // 5. spawn policy
        LastKnownLocationProvider provider = factories.lastKnownLocation() != null
                ? factories.lastKnownLocation()
                : SpawnPolicies.emptyProvider();
        SpawnPolicy spawnPolicy = SpawnPolicies.fromConfig(platform.spawnPolicy(), provider);

        // 6. storage lifecycle — the gate probes it for the degraded-storage refusal
        StorageLifecycle storage = null;
        if (factories.storage() != null) {
            try {
                storage = factories.storage().start(config);
            } catch (Exception e) {
                gateTimer.shutdownNow();
                closeQuietly(scheduler);
                closeQuietly(registrar);
                throw new CompositionException("storage", "the storage factory failed to start", e);
            }
        }
        StorageLifecycle storageRef = storage;
        StorageProbe probe = () -> storageRef == null ? StorageStatus.UP : storageRef.status();

        // 7. the join gate — restore handler installed, listeners armed last
        RestoreGate gate;
        try {
            gate = new RestoreGate(plugin, platform.gate(), messages, spawnPolicy, probe, gateTimer);
        } catch (RuntimeException e) {
            gateTimer.shutdownNow();
            closeQuietly(storage);
            closeQuietly(scheduler);
            closeQuietly(registrar);
            throw new CompositionException("gate", "could not create the join gate", e);
        }
        try {
            if (factories.restore() != null) {
                gate.installRestoreHandler(factories.restore().create(gate));
            }
            gate.arm();
        } catch (RuntimeException e) {
            closeQuietly(gate);
            gateTimer.shutdownNow();
            closeQuietly(storage);
            closeQuietly(scheduler);
            closeQuietly(registrar);
            throw new CompositionException("gate", "could not install the restore handler or arm the gate", e);
        }

        // 8. messaging integration — after the gate so handlers can rely on both
        MessagingRegistration messaging = null;
        if (factories.messaging() != null) {
            try {
                messaging = factories.messaging().install(registrar, config);
            } catch (Exception e) {
                closeQuietly(gate);
                gateTimer.shutdownNow();
                closeQuietly(storage);
                closeQuietly(scheduler);
                closeQuietly(registrar);
                throw new CompositionException("messaging", "the messaging factory failed to install", e);
            }
        }

        return new Composed(messages, scheduler, spawnPolicy, registrar, gate, storage, messaging, gateTimer);
    }

    private static ScheduledExecutorService newGateTimer() {
        var threadNumber = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "continuum-gate-timer-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        var executor = new ScheduledThreadPoolExecutor(2, factory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.warn("cleanup step failed during composition unwind", e);
        }
    }
}
