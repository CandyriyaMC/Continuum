package lol.candyriya.continuum.paper.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The bundled platform.yml seeds the node-local metrics toggle; this pins the
 * contract the metrics integration relies on: the platform loader must keep
 * working on a file that carries the {@code metrics:} section.
 */
class PlatformMetricsSectionTest {

    @TempDir
    Path dir;

    @Test
    void theSeededDefaultWithTheMetricsSectionStillLoadsThroughThePlatformLoader() {
        Path file = dir.resolve("platform.yml");
        PlatformConfigLoader.seedIfMissing(file);

        assertDoesNotThrow(() -> PlatformConfigLoader.load(file));
    }
}
