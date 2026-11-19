package lol.candyriya.continuum.paper.modules.metadata;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.SyncConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataModuleTogglesTest {

    /** A minimal config with only the module toggles set — everything else defaults. */
    private static SyncConfig config(Map<String, Boolean> modules) {
        return new SyncConfig(null, modules, null, null);
    }

    @Test
    @DisplayName("missing toggle defaults to enabled — first-party modules are opt-out")
    void defaultsEnabled() {
        SyncConfig config = config(Map.of());
        assertTrue(MetadataModuleToggles.metadataEnabled(config));
        assertEquals(List.of(BuiltinModules.METADATA), MetadataModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("sync.modules.metadata=false disables the metadata module")
    void disablesMetadataModule() {
        SyncConfig config = config(Map.of("metadata", false));
        assertFalse(MetadataModuleToggles.metadataEnabled(config));
        assertEquals(List.of(), MetadataModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("sync.modules.metadata=true stays enabled")
    void explicitlyEnabled() {
        SyncConfig config = config(Map.of("metadata", true));
        assertTrue(MetadataModuleToggles.metadataEnabled(config));
        assertEquals(List.of(BuiltinModules.METADATA), MetadataModuleToggles.enabledModuleIds(config));
    }

    @Test
    @DisplayName("unrelated toggles do not affect the metadata module")
    void unrelatedTogglesAreIgnored() {
        SyncConfig config = config(Map.of("vitals", false, "inventory", false, "pdc", false));
        assertTrue(MetadataModuleToggles.metadataEnabled(config));
    }

    @Test
    @DisplayName("the emitted id is the BuiltinModules constant — never hand-spelled")
    void idComesFromBuiltinModules() {
        assertEquals("continuum.metadata", BuiltinModules.METADATA);
        assertEquals(List.of(BuiltinModules.METADATA), MetadataModuleToggles.enabledModuleIds(config(Map.of())));
        assertEquals("metadata", MetadataModuleToggles.TOGGLE_KEY);
    }
}
