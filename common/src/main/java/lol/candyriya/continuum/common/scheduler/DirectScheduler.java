package lol.candyriya.continuum.common.scheduler;

/**
 * Executes everything inline on the calling thread.
 *
 * <p>Exists for unit tests and tools, not for production game loops: the
 * "async lane" runs synchronously here, which is exactly the determinism
 * tests want and the opposite of what a live server needs. Platform modules
 * ship real implementations; do not wire this into a running game.</p>
 */
public final class DirectScheduler implements PlatformScheduler {

    @Override
    public void global(Runnable task) {
        task.run();
    }

    @Override
    public void entity(EntityRef entity, Runnable task) {
        task.run();
    }

    @Override
    public void async(Runnable task) {
        task.run();
    }
}
