package lol.candyriya.continuum.paper.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Reserved slot for the metrics integration of this backend.
 *
 * <p>The wiring order in the backend is fixed, and this slot has its place in
 * it so the integration can land without reordering anything else. This build
 * ships no metrics implementation of its own; the metrics provider registers
 * itself here exactly once via {@link #register(AutoCloseable)}.</p>
 *
 * <p>One registration per slot, enforced. Registering twice is a bug in the
 * integration, and silencing it would hide configuration mistakes.</p>
 */
public final class MetricsSlot implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricsSlot.class);

    private AutoCloseable metrics;
    private boolean closed;

    /**
     * Registers the metrics implementation. Called at most once, by the metrics integration.
     *
     * @throws IllegalStateException when a metrics implementation is already registered, or the slot is closed
     */
    public synchronized void register(AutoCloseable metrics) {
        if (closed) {
            throw new IllegalStateException("metrics slot is closed");
        }
        if (this.metrics != null) {
            throw new IllegalStateException("a metrics implementation is already registered: " + this.metrics.getClass().getName());
        }
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        LOGGER.debug("metrics implementation registered: {}", metrics.getClass().getName());
    }

    /** The registered metrics implementation, or {@code null} when none was registered. */
    public synchronized AutoCloseable registered() {
        return metrics;
    }

    /** Unregisters by closing the registered implementation. Idempotent. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (metrics == null) {
            return;
        }
        try {
            metrics.close();
        } catch (Exception e) {
            // shutdown must not die here — log and move on
            LOGGER.warn("metrics implementation failed to close cleanly", e);
        } finally {
            metrics = null;
        }
    }
}
