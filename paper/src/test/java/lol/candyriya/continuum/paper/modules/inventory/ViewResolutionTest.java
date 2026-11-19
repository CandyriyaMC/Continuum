package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lol.candyriya.continuum.paper.modules.inventory.ViewResolution.ViewKind;

class ViewResolutionTest {

    @Test
    @DisplayName("internal 2×2 grid: crafting-typed top with a 4-slot matrix — nothing to close")
    void internalGrid() {
        assertEquals(ViewKind.INTERNAL_GRID, ViewResolution.resolve(true, true, 4));
    }

    @Test
    @DisplayName("workbench (crafting-typed, 9-slot matrix) counts as a foreign container")
    void workbenchIsForeign() {
        assertEquals(ViewKind.FOREIGN_CONTAINER, ViewResolution.resolve(true, true, 9));
    }

    @Test
    @DisplayName("chest/furnace/GUI top inventory is foreign — close it, do not transfer its contents")
    void chestIsForeign() {
        assertEquals(ViewKind.FOREIGN_CONTAINER, ViewResolution.resolve(true, false, -1));
    }

    @Test
    @DisplayName("no view at all — degrade gracefully, treat grid as empty")
    void noView() {
        assertEquals(ViewKind.NO_VIEW, ViewResolution.resolve(false, false, -1));
    }

    @Test
    @DisplayName("crafting-typed top with unknown matrix length is foreign — do not guess it into the internal grid")
    void unknownMatrixIsForeign() {
        assertEquals(ViewKind.FOREIGN_CONTAINER, ViewResolution.resolve(true, true, -1));
        assertEquals(ViewKind.FOREIGN_CONTAINER, ViewResolution.resolve(true, true, 0));
    }
}
