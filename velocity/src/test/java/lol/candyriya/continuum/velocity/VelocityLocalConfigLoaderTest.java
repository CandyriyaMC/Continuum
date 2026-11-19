package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.ConfigurationException;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityLocalConfigLoaderTest {

    @Test
    void typicalFileParses() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("velocity:\n  reconnect-debounce-seconds: 10\n");
        assertEquals(10, config.reconnectDebounceSeconds());
    }

    @Test
    void missingBlockYieldsDefaults() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("");
        assertEquals(VelocityTuning.DEFAULT.reconnectDebounceSeconds(), config.reconnectDebounceSeconds());
    }

    @Test
    void emptyVelocitySectionYieldsDefaults() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("velocity: {}\n");
        assertEquals(VelocityTuning.DEFAULT.reconnectDebounceSeconds(), config.reconnectDebounceSeconds());
    }

    @Test
    void outOfRangeValueRejectedWithPath() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  reconnect-debounce-seconds: 9999\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("velocity.reconnect-debounce-seconds")));
    }

    @Test
    void nonNumericValueRejectedWithPath() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  reconnect-debounce-seconds: fast\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("integer")));
    }

    @Test
    void unknownTopLevelKeyRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("bstats:\n  bstats-id: 1234\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("unknown top-level key")));
    }

    @Test
    void unknownNestedKeyRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  pending-connection-ttl: 30s\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("pending-connection-ttl")));
    }

    @Test
    void velocityBlockMustBeASection() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity: 3\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("'velocity' must be a section")));
    }

    @Test
    void seedWritesBundledDefaultWhenMissing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path target = dir.resolve(VelocityLocalConfigLoader.FILE_NAME);
        boolean written = VelocityLocalConfigLoader.seedIfMissing(target);
        assertTrue(written);
        assertTrue(Files.exists(target));
        // the seeded file must parse cleanly
        VelocityLocalConfig config = VelocityLocalConfigLoader.load(target);
        assertEquals(10, config.reconnectDebounceSeconds());
        // and a second seed does not overwrite it
        assertTrue(!VelocityLocalConfigLoader.seedIfMissing(target));
    }
}
