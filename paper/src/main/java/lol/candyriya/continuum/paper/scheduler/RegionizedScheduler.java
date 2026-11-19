package lol.candyriya.continuum.paper.scheduler;

import lol.candyriya.continuum.common.scheduler.EntityRef;
import lol.candyriya.continuum.common.scheduler.PlatformScheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared implementation of {@link PlatformScheduler} for the threaded-regions API
 * that Paper and Folia both ship (same class names, same semantics — Paper is one
 * giant region, Folia runs many in parallel).
 *
 * <p>Contract and failure policy:</p>
 * <ul>
 *   <li>Every submitted task is wrapped: uncaught exceptions are logged with the
 *       lane name, never propagated into the region tick loop.</li>
 *   <li>{@link #close()} stops the global and async lanes platform-side
 *       ({@code cancelTasks}), cancels every tracked task, and flips a closed flag;
 *       the wrapper drops stale entity tasks that are still queued behind it.</li>
 *   <li>Entity tasks whose entity is already gone are dropped with a warning —
 *       running them anywhere else would smuggle entity access onto the wrong thread,
 *       which is exactly what the abstraction exists to prevent.</li>
 *   <li>Entity tasks are untracked fire-and-forget (the API hands back no handle);
 *       their retired callback is mandatory — the entity may legitimately leave
 *       before the task runs.</li>
 * </ul>
 *
 * <p>Use {@link SchedulerFactory} to pick the platform-specific named subclass.</p>
 */
public abstract class RegionizedScheduler implements PlatformScheduler, AutoCloseable {

    private final Plugin plugin;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Set<ScheduledTask> tracked = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    RegionizedScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Platform name for diagnostics. */
    public abstract String platformName();

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("continuum scheduler is closed");
        }
    }

    @Override
    public void global(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().run(plugin, task1 -> {
            tracked.remove(task1);
            runGuarded("global", task);
        });
        tracked.add(scheduled);
    }

    @Override
    public void entity(EntityRef entity, Runnable task) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        ensureOpen();
        Entity target = Bukkit.getEntity(entity.entityId());
        if (target == null) {
            // no live entity — drop rather than run on the wrong thread
            logger.warn("entity task dropped: no live entity with id {} (already gone?)", entity.entityId());
            return;
        }
        EntityScheduler scheduler = target.getScheduler();
        boolean scheduled = scheduler.execute(plugin,
                () -> runGuarded("entity", task),
                () -> logger.info("entity task for {} retired before it ran (entity left)", entity.entityId()),
                0L);
        if (!scheduled) {
            logger.warn("entity task for {} was not scheduled (entity retiring?)", entity.entityId());
        }
    }

    @Override
    public void async(Runnable task) {
        Objects.requireNonNull(task, "task");
        ensureOpen();
        ScheduledTask scheduled = Bukkit.getAsyncScheduler().runNow(plugin, task1 -> {
            tracked.remove(task1);
            runGuarded("async", task);
        });
        tracked.add(scheduled);
    }

    private void runGuarded(String lane, Runnable task) {
        if (closed.get()) {
            // stale task queued before shutdown — drop quietly
            return;
        }
        try {
            task.run();
        } catch (Throwable t) {
            logger.error("uncaught exception in continuum {} task", lane, t);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // global + async lanes stop platform-side; entity lane relies on the guard in runGuarded
        try {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        } catch (Throwable t) {
            logger.warn("could not cancel global-region tasks (server shutting down?)", t);
        }
        try {
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } catch (Throwable t) {
            logger.warn("could not cancel async tasks (server shutting down?)", t);
        }
        for (ScheduledTask task : tracked) {
            try {
                task.cancel();
            } catch (Throwable t) {
                logger.warn("failed to cancel a tracked scheduled task", t);
            }
        }
        tracked.clear();
        logger.info("continuum scheduler closed ({})", platformName());
    }
}
