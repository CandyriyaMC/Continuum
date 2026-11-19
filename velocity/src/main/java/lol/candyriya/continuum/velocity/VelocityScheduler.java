package lol.candyriya.continuum.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;

import java.time.Duration;

/**
 * Thin wrapper over the proxy scheduler.
 *
 * <p>Everything Continuum schedules on the proxy goes through here: the proxy
 * scheduler is already async and concurrency-safe, tasks are tied to this
 * plugin for shutdown bookkeeping, and call sites stop spelling out the
 * plugin owner. The wrapper is intentionally tiny - retry/backoff logic
 * belongs to the components that need it.</p>
 */
public final class VelocityScheduler {

    private final Scheduler delegate;
    private final Object plugin;

    public VelocityScheduler(ProxyServer proxy, Object plugin) {
        this.delegate = proxy.getScheduler();
        this.plugin = plugin;
    }

    /** Runs the task once, as soon as the proxy gets around to it (always async). */
    public ScheduledTask runAsync(Runnable task) {
        return delegate.buildTask(plugin, task).schedule();
    }

    /** Runs the task once after {@code delay}. */
    public ScheduledTask scheduleDelayed(Runnable task, Duration delay) {
        return delegate.buildTask(plugin, task).delay(delay).schedule();
    }

    /** Runs the task after {@code initialDelay}, then every {@code period}. */
    public ScheduledTask scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
        return delegate.buildTask(plugin, task).delay(initialDelay).repeat(period).schedule();
    }
}
