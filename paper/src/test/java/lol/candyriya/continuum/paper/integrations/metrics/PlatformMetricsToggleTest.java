package lol.candyriya.continuum.paper.integrations.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformMetricsToggleTest {

    @TempDir
    Path dir;

    @Test
    void missingFileDefaultsToEnabled() {
        assertTrue(PlatformMetricsToggle.metricsEnabled(dir.resolve("platform.yml")));
    }

    @Test
    void sectionWithEnabledTrueReadsTrue() throws IOException {
        Path file = write("platform.yml", "metrics:\n  enabled: true\n");
        assertTrue(PlatformMetricsToggle.metricsEnabled(file));
    }

    @Test
    void sectionWithEnabledFalseReadsFalse() throws IOException {
        Path file = write("platform.yml", "metrics:\n  enabled: false\n");
        assertFalse(PlatformMetricsToggle.metricsEnabled(file));
    }

    @Test
    void missingSectionDefaultsToEnabled() throws IOException {
        Path file = write("platform.yml", "gate:\n  enabled: true\n");
        assertTrue(PlatformMetricsToggle.metricsEnabled(file));
    }

    @Test
    void missingKeyDefaultsToEnabled() throws IOException {
        Path file = write("platform.yml", "metrics:\n  other: thing\n");
        assertTrue(PlatformMetricsToggle.metricsEnabled(file));
    }

    @Test
    void corruptFileDefaultsToEnabledInsteadOfCrashing() throws IOException {
        Path file = write("platform.yml", "metrics: [ this is not: valid yaml\n");
        assertDoesNotThrow(() -> PlatformMetricsToggle.metricsEnabled(file));
        assertTrue(PlatformMetricsToggle.metricsEnabled(file));
    }

    @Test
    void nonBooleanEnabledDefaultsToEnabledInsteadOfCrashing() throws IOException {
        Path file = write("platform.yml", "metrics:\n  enabled: probably\n");
        assertDoesNotThrow(() -> PlatformMetricsToggle.metricsEnabled(file));
        assertTrue(PlatformMetricsToggle.metricsEnabled(file));
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
