package lol.candyriya.continuum.paper.modules.metadata;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.ModuleIds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-level checks that do not touch a live player; capture/restore
 * behavior is covered through the pure engine.
 */
class MetadataModuleTest {

    private final MetadataModule module = new MetadataModule();

    @Test
    void registersUnderTheBuiltinMetadataId() {
        assertEquals(BuiltinModules.METADATA, module.id());
        assertEquals("continuum.metadata", module.id());
        assertEquals(MetadataModule.MODULE_ID, module.id());
    }

    @Test
    void versionMatchesThePayloadFormat() {
        assertEquals(MetadataPayload.FORMAT, module.version());
        assertEquals(1, module.version());
    }

    @Test
    void declaresNoDependencies() {
        assertTrue(module.dependencies().isEmpty());
    }

    @Test
    void idIsWellFormedAndInTheReservedFirstPartyNamespace() {
        assertDoesNotThrow(() -> ModuleIds.requireValid(module.id()));
        assertTrue(ModuleIds.isReserved(module.id()));
    }
}
