package lol.candyriya.continuum.velocity.integrations.metrics;

import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.config.DatabaseConfig;
import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.KnownModules;
import lol.candyriya.continuum.common.config.RecoveryConfig;
import lol.candyriya.continuum.common.config.SyncConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsChartValuesTest {

    private static ContinuumConfig config(DatabaseType type, Map<String, Boolean> modules, List<String> restoreOrder) {
        var database = new DatabaseConfig(type, null, null);
        var recovery = new RecoveryConfig(true, Duration.ofSeconds(30), restoreOrder);
        var sync = new SyncConfig(null, modules, null, recovery);
        return new ContinuumConfig(false, null, database, null, sync, null, null);
    }

    @Test
    void storageBackendIsTheConfigTypeLowercased() {
        assertEquals("mysql", MetricsChartValues.storageBackend(config(DatabaseType.MYSQL, Map.of(), List.of())));
        assertEquals("mariadb", MetricsChartValues.storageBackend(config(DatabaseType.MARIADB, Map.of(), List.of())));
        assertEquals("mongodb", MetricsChartValues.storageBackend(config(DatabaseType.MONGODB, Map.of(), List.of())));
    }

    @Test
    void restoreOrderIsDefaultWhenNothingIsPinned() {
        assertEquals("default", MetricsChartValues.restoreOrder(config(DatabaseType.MYSQL, Map.of(), List.of())));
    }

    @Test
    void restoreOrderIsCustomWhenAnOrderIsPinned() {
        var pinned = List.of("vitals", "inventory");
        assertEquals("custom", MetricsChartValues.restoreOrder(config(DatabaseType.MYSQL, Map.of(), pinned)));
    }

    @Test
    void modulesEnabledDefaultsToEveryKnownModuleOn() {
        Map<String, Integer> values = MetricsChartValues.modulesEnabled(config(DatabaseType.MYSQL, Map.of(), List.of()));

        assertEquals(KnownModules.ALL.size(), values.size());
        assertEquals(Set.copyOf(KnownModules.ALL), values.keySet());
        values.forEach((id, value) -> assertEquals(1, value, "module " + id + " must default to on"));
    }

    @Test
    void modulesEnabledHonorsExplicitTogglesAndKeepsOrder() {
        Map<String, Integer> values = MetricsChartValues.modulesEnabled(
                config(DatabaseType.MYSQL, Map.of("pdc", false, "custom", false), List.of()));

        assertEquals(KnownModules.ALL, List.copyOf(values.keySet()));
        assertEquals(0, values.get(KnownModules.PDC));
        assertEquals(0, values.get(KnownModules.CUSTOM));
        assertEquals(1, values.get(KnownModules.INVENTORY));
        assertEquals(1, values.get(KnownModules.STATISTICS));
    }

    @Test
    void modulesEnabledDropsUnknownIdsWithoutFailing() {
        Map<String, Integer> values = MetricsChartValues.modulesEnabled(
                config(DatabaseType.MYSQL, Map.of("not-a-module", true, "inventory", false), List.of()));

        assertEquals(KnownModules.ALL.size(), values.size());
        assertTrue(values.keySet().stream().noneMatch(id -> "not-a-module".equals(id)));
        assertEquals(0, values.get(KnownModules.INVENTORY));
    }

    @Test
    void chartValuesCarryNoPersonalData() {
        Map<String, Integer> values = MetricsChartValues.modulesEnabled(config(DatabaseType.MYSQL, Map.of(), List.of()));

        for (var entry : values.entrySet()) {
            assertTrue(KnownModules.ALL.contains(entry.getKey()), "chart key must be a known module id");
            assertTrue(entry.getValue() == 0 || entry.getValue() == 1, "chart value must be a 0/1 toggle");
        }
        assertTrue(List.of("mysql", "mariadb", "mongodb").contains(
                MetricsChartValues.storageBackend(config(DatabaseType.MYSQL, Map.of(), List.of()))));
        assertTrue(List.of("default", "custom").contains(
                MetricsChartValues.restoreOrder(config(DatabaseType.MYSQL, Map.of(), List.of()))));
    }
}
