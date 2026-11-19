package lol.candyriya.continuum.paper.modules.pdc;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.ModuleIds;
import lol.candyriya.continuum.api.module.SyncModule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * The module id and schema version are persisted in every snapshot - they are
 * storage contract, not implementation detail. These tests pin them.
 *
 * <p>The module handle is typed as {@code SyncModule<?, ?>} on purpose: the test
 * runtime classpath deliberately does not see the platform API, so nothing here
 * may force loading the player handle type.</p>
 */
class PdcModuleContractTest {

    private static SyncModule<?, ?> module() {
        return new PdcModule();
    }

    @Test
    void idMatchesBuiltinConstant() {
        assertEquals(BuiltinModules.PDC, module().id());
        assertEquals("continuum.pdc", module().id());
    }

    @Test
    void idIsWellFormedAndFirstParty() {
        assertDoesNotThrow(() -> ModuleIds.requireValid(BuiltinModules.PDC));
        assertTrue(ModuleIds.isReserved(BuiltinModules.PDC));
    }

    @Test
    void schemaVersionIsOne() {
        assertEquals(1, module().version());
        assertEquals(PdcPayload.FORMAT, module().version());
    }

    @Test
    void declaresNoDependencies() {
        assertIterableEquals(java.util.List.of(), module().dependencies());
    }

    @Test
    void priorityIsTheDefault() {
        assertEquals(SyncModule.DEFAULT_PRIORITY, module().priority());
    }
}
