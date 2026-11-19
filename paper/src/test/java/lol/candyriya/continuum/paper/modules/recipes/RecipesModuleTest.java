package lol.candyriya.continuum.paper.modules.recipes;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-level checks that do not touch a live player; capture/restore
 * behavior is covered through the pure engines.
 */
class RecipesModuleTest {

    private final RecipesModule module = new RecipesModule();

    @Test
    void registersUnderTheBuiltinRecipesId() {
        assertEquals(BuiltinModules.RECIPES, module.id());
        assertEquals("continuum.recipes", module.id());
    }

    @Test
    void versionMatchesThePayloadFormat() {
        assertEquals(RecipesPayload.FORMAT, module.version());
    }

    @Test
    void declaresNoDependencies() {
        assertTrue(module.dependencies().isEmpty());
    }
}
