package lol.candyriya.continuum.storage.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatsTest {

    @Test
    void statReturnsStoredValue() {
        UUID player = UUID.randomUUID();
        PlayerStats stats = new PlayerStats(player, Map.of("games", 42L));
        assertEquals(42L, stats.stat("games"));
    }

    @Test
    void missingStatDefaultsToZero() {
        PlayerStats stats = new PlayerStats(UUID.randomUUID(), Map.of());
        assertEquals(0L, stats.stat("never-seen"));
    }

    @Test
    void valuesAreCopiedAndUnmodifiable() {
        Map<String, Long> mutable = new java.util.HashMap<>();
        mutable.put("a", 1L);
        PlayerStats stats = new PlayerStats(UUID.randomUUID(), mutable);
        mutable.put("b", 2L); // mutating the source must not leak in
        assertEquals(Map.of("a", 1L), stats.values());
        assertThrows(UnsupportedOperationException.class, () -> stats.values().put("c", 3L));
    }

    @Test
    void nullValuesBecomeEmpty() {
        PlayerStats stats = new PlayerStats(UUID.randomUUID(), null);
        assertTrue(stats.isEmpty());
    }

    @Test
    void nullPlayerIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new PlayerStats(null, Map.of()));
    }

    @Test
    void isEmptyReflectsContent() {
        UUID player = UUID.randomUUID();
        assertTrue(new PlayerStats(player, Map.of()).isEmpty());
        assertFalse(new PlayerStats(player, Map.of("a", 1L)).isEmpty());
    }
}
