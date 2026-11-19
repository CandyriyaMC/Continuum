package lol.candyriya.continuum.paper.modules.vitals;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.ModuleIds;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.paper.modules.experience.ExperienceModule;
import lol.candyriya.continuum.paper.modules.gamemode.GameModeModule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

/**
 * The module ids are persisted in every snapshot — they are storage contract,
 * not implementation detail. This test pins them to the public constants, and
 * guards the schema version and the vitals→attributes dependency edge.
 */
class ModuleIdParityTest {

    @Test
    void vitalsIdMatchesBuiltinConstant() {
        SyncModule<?, ?> module = new VitalsModule();
        assertEquals(BuiltinModules.VITALS, module.id());
        assertEquals("continuum.vitals", module.id());
    }

    @Test
    void experienceIdMatchesBuiltinConstant() {
        SyncModule<?, ?> module = new ExperienceModule();
        assertEquals(BuiltinModules.EXPERIENCE, module.id());
        assertEquals("continuum.experience", module.id());
    }

    @Test
    void gamemodeIdMatchesBuiltinConstant() {
        SyncModule<?, ?> module = new GameModeModule();
        assertEquals(BuiltinModules.GAMEMODE, module.id());
        assertEquals("continuum.gamemode", module.id());
    }

    @Test
    void idsAreWellFormedAndInTheReservedFirstPartyNamespace() {
        for (String id : new String[]{BuiltinModules.VITALS, BuiltinModules.EXPERIENCE, BuiltinModules.GAMEMODE}) {
            assertDoesNotThrow(() -> ModuleIds.requireValid(id));
            org.junit.jupiter.api.Assertions.assertTrue(ModuleIds.isReserved(id), id);
        }
    }

    @Test
    void idsAreDistinct() {
        assertEquals(3, java.util.Set.of(BuiltinModules.VITALS, BuiltinModules.EXPERIENCE, BuiltinModules.GAMEMODE).size());
    }

    @Test
    void schemaVersionsStartAtOne() {
        assertEquals(1, new VitalsModule().version());
        assertEquals(1, new ExperienceModule().version());
        assertEquals(1, new GameModeModule().version());
    }

    @Test
    void vitalsDeclaresAttributesDependency() {
        // max health restores via the attributes module before vitals writes current health
        assertIterableEquals(java.util.List.of(BuiltinModules.ATTRIBUTES), new VitalsModule().dependencies());
    }

    @Test
    void experienceAndGamemodeDeclareNoDependencies() {
        assertIterableEquals(java.util.List.of(), new ExperienceModule().dependencies());
        assertIterableEquals(java.util.List.of(), new GameModeModule().dependencies());
    }

    @Test
    void moduleIdsAreStableDottedLowercase() {
        assertEquals(java.util.regex.Pattern.compile("[a-z0-9]+(\\.[a-z0-9_-]+)+").matcher(BuiltinModules.VITALS).matches(), true);
        assertEquals(java.util.regex.Pattern.compile("[a-z0-9]+(\\.[a-z0-9_-]+)+").matcher(BuiltinModules.EXPERIENCE).matches(), true);
        assertEquals(java.util.regex.Pattern.compile("[a-z0-9]+(\\.[a-z0-9_-]+)+").matcher(BuiltinModules.GAMEMODE).matches(), true);
    }
}
