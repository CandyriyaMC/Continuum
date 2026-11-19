package lol.candyriya.continuum.paper.integrations.metrics;

import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The node-local metrics toggle, read straight from {@code platform.yml}.
 *
 * <p>The shared {@code config.yml} schema is closed and carries no metrics key,
 * so the opt-out lives here, next to the other node-local knobs. The read is
 * done with plain node navigation — the platform loader stays the only reader
 * of its own record, so this class works regardless of how the platform file
 * is mapped. Unknown sections are simply never inspected.</p>
 *
 * <p>Defaults to {@code true}: absent section or key, or an unreadable file,
 * all mean "metrics stay on" — the operator opted IN by default and can turn
 * this off here, or for every plugin at once via bStats' own global config.
 * A missing or broken file must never crash the backend, so any failure
 * short of a working read falls back to {@code true} with a warning.</p>
 */
public final class PlatformMetricsToggle {

    /** Node-local section this class reads from {@code platform.yml}. */
    public static final String SECTION = "metrics";
    /** Boolean key inside the section. */
    public static final String KEY = "enabled";

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformMetricsToggle.class);

    private PlatformMetricsToggle() {
    }

    /**
     * Reads {@code metrics.enabled} from {@code platformConfig}.
     *
     * @param platformConfig path to {@code platform.yml}
     * @return the effective toggle; {@code true} on any read problem (logged)
     */
    public static boolean metricsEnabled(Path platformConfig) {
        try {
            if (!Files.exists(platformConfig)) {
                LOGGER.warn("{} does not exist; defaulting metrics to enabled", platformConfig);
                return true;
            }
            var root = YamlConfigurationLoader.builder().path(platformConfig).build().load();
            return root.node(SECTION, KEY).getBoolean(true);
        } catch (Throwable e) {
            // never take the backend down over a metrics toggle
            LOGGER.warn("could not read '{}.{}' from {}; defaulting metrics to enabled",
                    SECTION, KEY, platformConfig, e);
            return true;
        }
    }
}
