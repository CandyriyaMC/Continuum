package lol.candyriya.continuum.velocity.integrations.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyMetricsToggleTest {

    @TempDir
    Path dir;

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(ProxyMetricsToggleTest.class);

    @Test
    void missingFileIsSeededWithMetricsEnabled() {
        assertTrue(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));

        Path file = dir.resolve(ProxyMetricsToggle.FILE_NAME);
        assertTrue(Files.exists(file), "first run must seed metrics.yml");
        assertTrue(readString(file).contains("enabled: true"));
    }

    @Test
    void seededFileIsNeverOverwritten() throws IOException {
        Path file = dir.resolve(ProxyMetricsToggle.FILE_NAME);
        Files.writeString(file, "enabled: false\n");

        assertFalse(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
        assertTrue(readString(file).contains("enabled: false"), "the operator's file must be left alone");
    }

    @Test
    void disabledReadsFalse() throws IOException {
        Files.writeString(dir.resolve(ProxyMetricsToggle.FILE_NAME), "enabled: false\n");
        assertFalse(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
    }

    @Test
    void missingKeyDefaultsToEnabled() throws IOException {
        Files.writeString(dir.resolve(ProxyMetricsToggle.FILE_NAME), "# nothing set\n");
        assertTrue(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
    }

    @Test
    void corruptFileDefaultsToEnabledInsteadOfCrashing() throws IOException {
        Path file = dir.resolve(ProxyMetricsToggle.FILE_NAME);
        Files.writeString(file, "enabled: [ not: valid yaml\n");

        assertDoesNotThrow(() -> ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
        assertTrue(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
    }

    @Test
    void nonBooleanEnabledDefaultsToEnabledInsteadOfCrashing() throws IOException {
        Path file = dir.resolve(ProxyMetricsToggle.FILE_NAME);
        Files.writeString(file, "enabled: probably\n");

        assertDoesNotThrow(() -> ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
        assertTrue(ProxyMetricsToggle.metricsEnabled(dir, LOGGER));
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
