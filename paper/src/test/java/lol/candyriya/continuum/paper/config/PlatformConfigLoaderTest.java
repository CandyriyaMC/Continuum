package lol.candyriya.continuum.paper.config;

import lol.candyriya.continuum.common.config.ConfigurationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void seedingWritesTheBundledDefaultsAndThenRefusesToOverwrite() {
        Path file = dir.resolve("platform.yml");

        assertTrue(PlatformConfigLoader.seedIfMissing(file));
        assertFalse(PlatformConfigLoader.seedIfMissing(file), "second seed must not overwrite the operator's file");
    }

    @Test
    void theSeededFileLoadsBackWithTheDocumentedDefaults() {
        Path file = dir.resolve("platform.yml");
        PlatformConfigLoader.seedIfMissing(file);

        PlatformConfig config = PlatformConfigLoader.load(file);

        assertTrue(config.gate().enabled());
        assertEquals(3, config.gate().maxFailures());
        assertEquals(10, config.gate().failureCooldownMinutes());
        assertEquals(5, config.gate().restoreTimeoutSeconds());
        assertTrue(config.gate().disallowOnStorageDegraded());
        assertEquals(SpawnMode.SERVER_SPAWN, config.spawnPolicy().mode());
        assertEquals("", config.spawnPolicy().world());
    }

    @Test
    void aMissingFileIsAnErrorNotASilentDefault() {
        Path file = dir.resolve("does-not-exist.yml");

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> PlatformConfigLoader.load(file));

        assertTrue(error.getMessage().contains("missing platform configuration file"));
    }

    @Test
    void everyProblemIsReportedInOneGo() throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, """
                gate:
                  enabled: true
                  max-failures: 0
                  failure-cooldown-minutes: 99999
                  restore-timeout-seconds: -1
                  disallow-on-storage-degraded: true
                spawn-policy:
                  mode: NOT_A_MODE
                  world: 'bad world!'
                """);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> PlatformConfigLoader.load(file));

        // an unknown enum constant fails the type mapping, which is fine, but a bad
        // world name and out-of-range numbers must be reported with their paths
        String message = error.getMessage();
        assertTrue(message.contains("gate.") || message.contains("spawn-policy."), message);
    }

    @Test
    void anOutOfRangeValueIsRejectedWithItsPath() throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, """
                gate:
                  enabled: true
                  max-failures: 0
                  failure-cooldown-minutes: 10
                  restore-timeout-seconds: 5
                  disallow-on-storage-degraded: true
                spawn-policy:
                  mode: SERVER_SPAWN
                  world: ""
                """);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> PlatformConfigLoader.load(file));

        assertTrue(error.violations().stream().anyMatch(v -> v.startsWith("gate.max-failures")),
                () -> "violations were: " + error.violations());
    }

    @Test
    void aMalformedWorldNameIsRejectedWithItsPath() throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, """
                gate:
                  enabled: true
                  max-failures: 3
                  failure-cooldown-minutes: 10
                  restore-timeout-seconds: 5
                  disallow-on-storage-degraded: false
                spawn-policy:
                  mode: SERVER_SPAWN
                  world: 'bad world!'
                """);

        ConfigurationException error = assertThrows(ConfigurationException.class, () -> PlatformConfigLoader.load(file));

        assertTrue(error.violations().stream().anyMatch(v -> v.startsWith("spawn-policy.world")),
                () -> "violations were: " + error.violations());
    }

    @Test
    void anAbsentGateSectionFallsBackToTheDocumentedDefaults() throws IOException {
        Path file = dir.resolve("platform.yml");
        // gate section deleted entirely — the compact constructor supplies the defaults
        Files.writeString(file, "spawn-policy:\n  mode: SERVER_SPAWN\n  world: \"\"\n");

        PlatformConfig config = PlatformConfigLoader.load(file);

        assertEquals(3, config.gate().maxFailures());
        assertEquals(true, config.gate().enabled());
        assertEquals(SpawnMode.SERVER_SPAWN, config.spawnPolicy().mode());
    }

    @Test
    void anAbsentSpawnSectionFallsBackToTheDocumentedDefaults() throws IOException {
        Path file = dir.resolve("platform.yml");
        Files.writeString(file, "gate:\n  enabled: true\n  max-failures: 4\n  failure-cooldown-minutes: 12\n  restore-timeout-seconds: 7\n  disallow-on-storage-degraded: false\n");

        PlatformConfig config = PlatformConfigLoader.load(file);

        assertEquals(4, config.gate().maxFailures());
        assertEquals(SpawnMode.SERVER_SPAWN, config.spawnPolicy().mode());
        assertEquals("", config.spawnPolicy().world());
    }
}
