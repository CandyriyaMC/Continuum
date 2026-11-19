package lol.candyriya.continuum.velocity.integrations.metrics;

import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The node-local metrics toggle for the proxy, with self-seeding.
 *
 * <p>The proxy's {@code velocity.yml} rejects unknown keys by design, so the
 * opt-out lives in its own tiny {@code metrics.yml} right next to it. The file
 * is seeded on first run with {@code enabled: true} and left alone afterwards;
 * deleting it just re-seeds the default. Like {@code velocity.yml}, this file
 * is the operator's — nothing ever overwrites it.</p>
 *
 * <p>Defaults to {@code true}: a missing key or an unreadable file means
 * "metrics stay on" — the operator opted IN by default and can turn this off
 * here, or for every plugin at once via bStats' own global config. Any failure
 * short of a working read falls back to {@code true} with a warning, and never
 * takes the proxy down.</p>
 */
public final class ProxyMetricsToggle {

    /** File name used inside the plugin data directory. */
    public static final String FILE_NAME = "metrics.yml";

    /** The content written when seeding a missing file. */
    static final String DEFAULT_CONTENT = """
            # Continuum proxy metrics (bStats). Anonymous usage statistics:
            # server count, version distribution and a few configuration values.
            # No personal data is ever collected.
            # Set enabled to false to stop all metrics reporting from this proxy.
            # A global opt-out shared by all plugins is also available:
            # plugins/bStats/config.txt -> enabled: false
            enabled: true
            """;

    private ProxyMetricsToggle() {
    }

    /**
     * Reads {@code enabled} from {@code <dataDirectory>/metrics.yml}, seeding the
     * file first when it is missing.
     *
     * @param dataDirectory the plugin's data directory (e.g. {@code plugins/continuum})
     * @param logger        proxy logger
     * @return the effective toggle; {@code true} on any read problem (logged)
     */
    public static boolean metricsEnabled(Path dataDirectory, Logger logger) {
        try {
            Path file = dataDirectory.resolve(FILE_NAME);
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                Files.writeString(file, DEFAULT_CONTENT);
                logger.info("seeded {} (bStats metrics, enabled by default — set enabled: false there to opt out)", file);
                return true;
            }
            var root = YamlConfigurationLoader.builder().path(file).build().load();
            return root.node("enabled").getBoolean(true);
        } catch (Throwable e) {
            // never take the proxy down over a metrics toggle
            logger.warn("could not read the metrics toggle in {}; defaulting metrics to enabled", dataDirectory.resolve(FILE_NAME), e);
            return true;
        }
    }
}
