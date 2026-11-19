package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.config.ConfigurationException;
import lol.candyriya.continuum.velocity.VelocityLocalConfig;
import lol.candyriya.continuum.velocity.VelocityLocalConfigLoader;
import lol.candyriya.continuum.velocity.VelocityTuning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture timeout is part of the switch contract: it bounds how long a
 * player waits on the connection pipeline for the backend to persist their
 * pre-switch snapshot before the proxy refuses the switch. These tests pin
 * the key's parsing and range.
 */
class VelocityLocalConfigCaptureKeyTest {

    @Test
    void newKeyParsesAndIsExposed() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load(
                "velocity:\n  reconnect-debounce-seconds: 10\n  capture-timeout-seconds: 7\n");
        assertEquals(7, config.captureTimeoutSeconds());
        // existing keys keep working and keep their defaults
        assertEquals(10, config.reconnectDebounceSeconds());
        assertEquals(8, config.handoffRestoreTimeoutSeconds());
    }

    @Test
    void missingKeyYieldsTheDefault() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("velocity:\n  reconnect-debounce-seconds: 10\n");
        assertEquals(VelocityTuning.DEFAULT_CAPTURE_TIMEOUT_SECONDS, config.captureTimeoutSeconds());
        assertEquals(3, config.captureTimeoutSeconds());
    }

    @Test
    void emptyConfigYieldsAllDefaults() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("");
        assertEquals(VelocityTuning.DEFAULT.reconnectDebounceSeconds(), config.reconnectDebounceSeconds());
        assertEquals(VelocityTuning.DEFAULT_RESTORE_TIMEOUT_SECONDS, config.handoffRestoreTimeoutSeconds());
        assertEquals(VelocityTuning.DEFAULT_CAPTURE_TIMEOUT_SECONDS, config.captureTimeoutSeconds());
    }

    @Test
    void outOfRangeHighRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  capture-timeout-seconds: 11\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("velocity.capture-timeout-seconds")));
    }

    @Test
    void outOfRangeLowRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  capture-timeout-seconds: 0\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("velocity.capture-timeout-seconds")));
    }

    @Test
    void nonNumericRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  capture-timeout-seconds: fast\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("integer")));
    }

    @Test
    void unknownKeyErrorNamesTheNewKeyToo() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  pending-connection-ttl: 30s\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("capture-timeout-seconds")));
    }
}
