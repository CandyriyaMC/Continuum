package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadMigrationsTest {

    @Test
    @DisplayName("current format is accepted")
    void acceptsCurrent() {
        assertEquals(PayloadMigrations.CURRENT_FORMAT, PayloadMigrations.requireSupported(1));
    }

    @Test
    @DisplayName("stale (older) format is rejected — never best-effort decodes")
    void rejectsStale() {
        var e = assertThrows(InventoryStateException.class, () -> PayloadMigrations.requireSupported(0));
        assertTrue(e.getMessage().contains("0"));
    }

    @Test
    @DisplayName("future format is rejected — a newer snapshot on an older server must fail, not corrupt")
    void rejectsFuture() {
        var e = assertThrows(InventoryStateException.class, () -> PayloadMigrations.requireSupported(2));
        assertTrue(e.getMessage().contains("2"));
    }

    @Test
    @DisplayName("garbage format is rejected")
    void rejectsGarbage() {
        assertThrows(InventoryStateException.class, () -> PayloadMigrations.requireSupported(-7));
    }
}
