package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.snapshot.SnapshotMigrationRunner;
import lol.candyriya.continuum.paper.backend.BackendComposition;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the backend sync graph: builds it from the shared config at composition
 * time, tears it down before the storage/gate do on shutdown.
 *
 * <p>One instance per plugin lifetime, created in {@code onEnable};
 * {@link #factories()} feeds the composition root. The storage factory runs
 * first (the composition decides that order); the restore factory runs after
 * the gate is created and before listeners are armed, and it is where the
 * whole graph — FIFO queues, dirty tracking, LKG, snapshot manager, restore
 * orchestrator, Bukkit listeners — comes to life.</p>
 *
 * <p>Close order ({@link #close()}) — always run this <em>before</em>
 * {@code PaperServices.close()}: best-effort full capture of every online
 * player, session releases, listener unregistration, timer and FIFO shutdown.
 * The storage itself is closed by the composition afterwards, so the shutdown
 * capture still has a database to write to.</p>
 *
 * <p>No static state; everything hangs off the instance.</p>
 */
public final class OrchestrationWiring implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrchestrationWiring.class);

    private final JavaPlugin plugin;
    private final OrchestrationSettings settings;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Filled by the factories once they run; used by {@link #close()} and diagnostics. */
    private final AtomicReference<Graph> graph = new AtomicReference<>();

    private OrchestrationWiring(JavaPlugin plugin, OrchestrationSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Production wiring with default knobs. */
    public static OrchestrationWiring create(JavaPlugin plugin) {
        return new OrchestrationWiring(plugin, OrchestrationSettings.defaults());
    }

    /** Test wiring with shrunk knobs. */
    public static OrchestrationWiring create(JavaPlugin plugin, OrchestrationSettings settings) {
        return new OrchestrationWiring(plugin, settings);
    }

    /**
     * The factories for {@code PaperServices.start}. The storage factory picks
     * SQL or MongoDB from {@code database.type} and starts health probing; the
     * restore factory builds the rest of the graph once the gate exists.
     */
    public BackendComposition.Factories factories() {
        SharedBuild shared = new SharedBuild();
        var storageFactory = (lol.candyriya.continuum.paper.backend.StorageLifecycleFactory) config -> {
            BackendStorage storage = BackendStorage.start(config);
            ModuleRuntime modules = ModuleRuntime.builtins(config);
            shared.config = config;
            shared.storage = storage;
            shared.modules = modules;
            return storage;
        };
        // the capture responder answers continuum.capture.request from the proxy; install runs after the
        // graph exists (composition step 8), so the transfer participant is resolvable by then
        var messagingInstaller = (lol.candyriya.continuum.paper.backend.MessagingInstaller) (registrar, config) -> {
            if (!config.messaging().pluginMessage().enabled()) {
                LOGGER.info("plugin messaging disabled; capture responder not registered");
                return null;
            }
            Graph g = graph.get();
            if (g == null) {
                throw new IllegalStateException(
                        "messaging installed before the orchestration graph — the capture responder has no transfer participant");
            }
            String channel = config.messaging().pluginMessage().channel();
            var participant = new lol.candyriya.continuum.paper.transfer.TransferParticipant(g.snapshots());
            var responder = new lol.candyriya.continuum.paper.messaging.CaptureResponder(plugin, participant, channel, LOGGER);
            registrar.registerIncoming(channel, responder);
            LOGGER.info("capture responder registered on '{}'", channel);
            return (lol.candyriya.continuum.paper.backend.MessagingRegistration) () ->
                    registrar.unregisterIncoming(channel, responder);
        };
        var restoreFactory = (lol.candyriya.continuum.paper.backend.RestoreHandlerFactory) gate -> {
            if (shared.storage == null || shared.config == null || shared.modules == null) {
                throw new IllegalStateException(
                        "storage lifecycle has not started yet — the restore factory must run after it");
            }
            ContinuumConfig config = shared.config;
            BackendStorage storage = shared.storage;
            ModuleRuntime modules = shared.modules;
            // no schema steps exist yet (schema version 1); the runner becomes the
            // migration seam when the schema bumps — constructed explicitly so it is obvious
            SnapshotMigrationRunner migrations = new SnapshotMigrationRunner(java.util.List.of());

            PlayerFifoQueue fifo = new PlayerFifoQueue(settings);
            DirtyTracker dirty = new DirtyTracker();
            SessionRegistry sessions = new SessionRegistry();
            LastKnownGoodStore lastKnownGood = new LastKnownGoodStore();
            EventDispatcher events = new EventDispatcher();

            SnapshotManager snapshots = new SnapshotManager(plugin, config, modules, fifo, dirty,
                    sessions, storage, storage, events, settings, gate::isRestoring, lastKnownGood);
            snapshots.start();

            RestoreOrchestrator restore = new RestoreOrchestrator(gate, plugin, config, modules, fifo,
                    dirty, sessions, storage, events, settings, lastKnownGood, migrations);

            var quitListener = new OrchestrationListeners(snapshots);
            var dirtyListener = new DirtyTrackingListener(dirty);
            plugin.getServer().getPluginManager().registerEvents(quitListener, plugin);
            plugin.getServer().getPluginManager().registerEvents(dirtyListener, plugin);

            Graph built = new Graph(restore, snapshots, fifo, quitListener, dirtyListener, events);
            if (!graph.compareAndSet(null, built)) {
                closeQuietly(built);
                throw new IllegalStateException("orchestration wiring was initialized twice");
            }
            LOGGER.info("sync pipeline wired (modules={}, backend={})",
                    modules.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList(),
                    config.network().serverId());
            return restore;
        };
        return new BackendComposition.Factories(storageFactory, messagingInstaller, restoreFactory, null);
    }

    /** The transfer-side handle, once the graph is up; null before the restore factory ran. */
    public lol.candyriya.continuum.paper.transfer.TransferParticipant transfer() {
        Graph g = graph.get();
        return g == null ? null : new lol.candyriya.continuum.paper.transfer.TransferParticipant(g.snapshots());
    }

    /** How many subscriptions the internal event dispatcher carries; diagnostics. */
    public int eventSubscriptionCount() {
        Graph g = graph.get();
        return g == null ? 0 : g.events().subscriptionCount();
    }

    /**
     * Tears down the graph: shutdown capture (best effort), session releases,
     * listener unregistration, FIFO shutdown. Idempotent; never throws.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Graph g = graph.getAndSet(null);
        if (g == null) {
            return;
        }
        unregisterQuietly(g.quitListener());
        unregisterQuietly(g.dirtyListener());
        closeQuietly(g);
        LOGGER.info("sync pipeline closed");
    }

    private void unregisterQuietly(org.bukkit.event.Listener listener) {
        try {
            org.bukkit.event.HandlerList.unregisterAll(listener);
        } catch (Throwable t) {
            LOGGER.warn("could not unregister a listener cleanly", t);
        }
    }

    private static void closeQuietly(Graph g) {
        try {
            // shutdown capture + releases BEFORE the FIFO goes down; storage still open
            g.snapshots().close();
        } catch (Throwable t) {
            LOGGER.error("shutdown capture blew up; state reverts to the last saved snapshot", t);
        }
        try {
            g.restore().close();
        } catch (Throwable t) {
            LOGGER.warn("closing the restore orchestrator failed; continuing", t);
        }
        try {
            g.fifo().close();
        } catch (Throwable t) {
            LOGGER.warn("closing the fifo queue failed; continuing", t);
        }
    }

    /** The shared state passed from the storage factory to the restore factory. */
    private static final class SharedBuild {

        private volatile ContinuumConfig config;
        private volatile BackendStorage storage;
        private volatile ModuleRuntime modules;
    }

    /** The built graph, kept so {@link #close()} can tear it down in the right order. */
    private record Graph(RestoreOrchestrator restore,
                         SnapshotManager snapshots,
                         PlayerFifoQueue fifo,
                         OrchestrationListeners quitListener,
                         DirtyTrackingListener dirtyListener,
                         EventDispatcher events) {
    }
}
