package lol.candyriya.continuum.paper.integrations.metrics;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.config.KnownModules;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The values behind the bStats custom charts. Pure functions of the loaded
 * configuration: no server state, no player data, nothing personal — the
 * charts describe the node's configuration, never its users.
 *
 * <p>Only values derivable from the shared config are exposed. Anything that
 * would need platform state (player counts, restore results, durations) is
 * deliberately absent: bStats already reports the player amount, and inventing
 * numbers here would be dishonest.</p>
 */
public final class MetricsChartValues {

    private MetricsChartValues() {
    }

    /** {@code mysql} | {@code mariadb} | {@code mongodb} — storage engine from {@code database.type}. */
    public static String storageBackend(ContinuumConfig config) {
        return config.database().type().name().toLowerCase(Locale.ROOT);
    }

    /** {@code custom} when the operator pinned a {@code sync.recovery.restore-order}, else {@code default}. */
    public static String restoreOrder(ContinuumConfig config) {
        return config.sync().recovery().restoreOrder().isEmpty() ? "default" : "custom";
    }

    /**
     * Every known module id mapped to {@code 1} (effective toggle on) or {@code 0}
     * (off), in {@link KnownModules#ALL} order. Missing ids default to enabled,
     * exactly like the runtime does; unknown ids never appear because the common
     * config only speaks in known ids.
     */
    public static Map<String, Integer> modulesEnabled(ContinuumConfig config) {
        Map<String, Boolean> effective = config.sync().modulesWithDefaults();
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String id : KnownModules.ALL) {
            values.put(id, effective.getOrDefault(id, true) ? 1 : 0);
        }
        return values;
    }
}
