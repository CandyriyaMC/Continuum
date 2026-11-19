package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.SyncConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryModuleTogglesTest {

    /** A minimal config with only the module toggles set — everything else defaults. */
    private static SyncConfig config(Map<String, Boolean> modules) {
        return new SyncConfig(null, modules, null, null);
    }

    @Test
    @DisplayName("missing toggles default to enabled — first-party modules are opt-out")
    void defaultsEnabled() {
        SyncConfig config = config(Map.of());
        assertTrue(InventoryModuleToggles.inventoryEnabled(config));
        assertTrue(InventoryModuleToggles.enderChestEnabled(config));
        assertEquals(List.of(BuiltinModules.INVENTORY, BuiltinModules.ENDERCHEST),
                InventoryModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("sync.modules.inventory=false disables only the inventory module — ender chest keeps running")
    void disablesInventoryOnly() {
        SyncConfig config = config(Map.of("inventory", false));
        assertFalse(InventoryModuleToggles.inventoryEnabled(config));
        assertTrue(InventoryModuleToggles.enderChestEnabled(config));
        assertEquals(List.of(BuiltinModules.ENDERCHEST), InventoryModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("sync.modules.ender-chest=false disables only the ender chest module — inventory keeps running")
    void disablesEnderChestOnly() {
        SyncConfig config = config(Map.of("ender-chest", false));
        assertTrue(InventoryModuleToggles.inventoryEnabled(config));
        assertFalse(InventoryModuleToggles.enderChestEnabled(config));
        assertEquals(List.of(BuiltinModules.INVENTORY), InventoryModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("both toggles off — no ids are registered")
    void disablesBoth() {
        SyncConfig config = config(Map.of("inventory", false, "ender-chest", false));
        assertFalse(InventoryModuleToggles.inventoryEnabled(config));
        assertFalse(InventoryModuleToggles.enderChestEnabled(config));
        assertEquals(List.of(), InventoryModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("the emitted ids are exactly the BuiltinModules constants — never hand-spelled")
    void idsComeFromBuiltinModules() {
        assertEquals("continuum.inventory", BuiltinModules.INVENTORY);
        assertEquals("continuum.enderchest", BuiltinModules.ENDERCHEST);
        assertTrue(InventoryModuleToggles.enabledModuleIds(config(Map.of()))
                .stream().allMatch(id -> id.equals(BuiltinModules.INVENTORY) || id.equals(BuiltinModules.ENDERCHEST)));
    }
}
