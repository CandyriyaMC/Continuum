package lol.candyriya.continuum.velocity;

/**
 * Metrics seam for the proxy.
 *
 * <p>Deliberately not bound to any concrete exporter yet. The counters and
 * gauges the proxy cares about are emitted through this interface so a real
 * provider (bStats, Micrometer, anything) can be dropped in without touching
 * the code paths that increment them. The default wiring is {@link #NOOP},
 * which does nothing and allocates nothing.</p>
 */
public interface Metrics {

    /** Increments a monotonic counter. Names are dotted, e.g. {@code continuum.sessions.connected}. */
    void increment(String counter);

    /** Sets a gauge to the given double value. */
    void gauge(String gauge, double value);

    /** No-op provider; the default until a real exporter is wired in. */
    Metrics NOOP = new Metrics() {
        @Override
        public void increment(String counter) {
        }

        @Override
        public void gauge(String gauge, double value) {
        }
    };
}
