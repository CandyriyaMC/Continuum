package lol.candyriya.continuum.paper.integrations.metrics;

/**
 * The one gate every metrics path passes through before anything touches the
 * network.
 *
 * <p>Metrics ship disabled by default in this build: the bStats service id is
 * assigned when the plugin is registered on bstats.org at first release, and
 * until {@link BukkitMetricsService#BSTATS_PLUGIN_ID} is set to a positive
 * number, no HTTP request ever leaves the process — the gate says so, and the
 * services never construct a bStats exporter at all.</p>
 */
public enum MetricsGate {

    /** bStats is registered (id &gt; 0) and the operator did not opt out. */
    ENABLED,

    /** The operator opted out in the node-local config ({@code metrics.enabled: false}). */
    DISABLED_BY_CONFIG,

    /** No bStats service id is set yet — registration happens at first release. */
    NOT_REGISTERED;

    /**
     * Resolves the gate.
     *
     * @param enabled  node-local metrics toggle (defaults to true when unset)
     * @param pluginId the bStats service id constant; {@code &gt; 0} means registered
     */
    public static MetricsGate decide(boolean enabled, int pluginId) {
        if (!enabled) {
            return DISABLED_BY_CONFIG;
        }
        return pluginId > 0 ? ENABLED : NOT_REGISTERED;
    }
}
