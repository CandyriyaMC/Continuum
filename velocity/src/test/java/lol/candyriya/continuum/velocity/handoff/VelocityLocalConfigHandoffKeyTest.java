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
 * The handoff restore timeout is part of the handoff contract: it bounds how
 * long a player sits frozen on a target backend before the proxy lets go of
 * the transfer. These tests pin the key's parsing and range.
 */
class VelocityLocalConfigHandoffKeyTest {

    @Test
    void newKeyParsesAndIsExposed() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load(
                "velocity:\n  reconnect-debounce-seconds: 10\n  handoff-restore-timeout-seconds: 42\n");
        assertEquals(42, config.handoffRestoreTimeoutSeconds());
        // the old key keeps working and keeps its default
        assertEquals(10, config.reconnectDebounceSeconds());
    }

    @Test
    void missingKeyYieldsTheDefault() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("velocity:\n  reconnect-debounce-seconds: 10\n");
        assertEquals(VelocityTuning.DEFAULT_RESTORE_TIMEOUT_SECONDS, config.handoffRestoreTimeoutSeconds());
        assertEquals(8, config.handoffRestoreTimeoutSeconds());
    }

    @Test
    void emptyConfigYieldsBothDefaults() {
        VelocityLocalConfig config = VelocityLocalConfigLoader.load("");
        assertEquals(VelocityTuning.DEFAULT.reconnectDebounceSeconds(), config.reconnectDebounceSeconds());
        assertEquals(VelocityTuning.DEFAULT_RESTORE_TIMEOUT_SECONDS, config.handoffRestoreTimeoutSeconds());
    }

    @Test
    void outOfRangeHighRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  handoff-restore-timeout-seconds: 121\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("velocity.handoff-restore-timeout-seconds")));
    }

    @Test
    void outOfRangeLowRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  handoff-restore-timeout-seconds: 0\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("velocity.handoff-restore-timeout-seconds")));
    }

    @Test
    void nonNumericRejected() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  handoff-restore-timeout-seconds: fast\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("integer")));
    }

    @Test
    void unknownKeyErrorNamesTheNewKeyToo() {
        var exception = assertThrows(ConfigurationException.class,
                () -> VelocityLocalConfigLoader.load("velocity:\n  pending-connection-ttl: 30s\n"));
        assertTrue(exception.violations().stream().anyMatch(v -> v.contains("handoff-restore-timeout-seconds")));
    }
}
