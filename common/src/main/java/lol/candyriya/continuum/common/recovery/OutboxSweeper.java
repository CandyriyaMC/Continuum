package lol.candyriya.continuum.common.recovery;

/**
 * Periodic drain for the transactional outbox. Implementations poll the
 * outbox on a fixed delay, publish confirmed entries and record outcomes;
 * failures are retried on subsequent ticks, never by blocking the loop.
 *
 * <p>Implementations must be safe to start and stop from any thread, and
 * {@link #close()} must stop the loop without waiting forever for an
 * in-flight sweep.</p>
 */
public interface OutboxSweeper extends AutoCloseable {

    /**
     * Starts the periodic drain. A no-op when the sweeper is disabled. Calling
     * it while already running does nothing.
     */
    void start();

    /** True while the drain loop is active. */
    boolean running();

    /** Live counters; snapshot them, do not keep the instance for accounting. */
    OutboxSweepStats stats();

    /**
     * Stops the drain loop, giving an in-flight sweep a bounded chance to
     * finish before interrupting it. Idempotent; after close the sweeper must
     * be started again via {@link #start()}.
     */
    @Override
    void close();
}
