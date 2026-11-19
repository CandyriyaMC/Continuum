package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigTest {

    @Test
    void defaultsKeepTenAndNinetySeconds() {
        StorageConfig config = StorageConfig.defaults();
        assertEquals(10, config.historyKeepLast());
        assertEquals(Duration.ofSeconds(90), config.sessionLease());
        assertFalse(config.leakDetection());
    }

    @Test
    void historyKeepMustBeAtLeastOne() {
        assertThrows(IllegalArgumentException.class, () -> new StorageConfig(0, Duration.ofSeconds(1), false));
        new StorageConfig(1, Duration.ofSeconds(1), false); // boundary is fine
    }

    @Test
    void leaseMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new StorageConfig(1, Duration.ZERO, false));
        assertThrows(IllegalArgumentException.class, () -> new StorageConfig(1, Duration.ofSeconds(-5), false));
        assertThrows(NullPointerException.class, () -> new StorageConfig(1, null, false));
    }

    @Test
    void leakDetectionCanBeEnabled() {
        assertTrue(new StorageConfig(5, Duration.ofMinutes(1), true).leakDetection());
    }
}
