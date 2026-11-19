package lol.candyriya.continuum.paper.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirtyTrackerTest {

    @Test
    void marksAndChecksPerModule() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();

        assertFalse(tracker.isDirty(player, "continuum.vitals"));
        tracker.markDirty(player, "continuum.vitals");
        assertTrue(tracker.isDirty(player, "continuum.vitals"));
        assertFalse(tracker.isDirty(player, "continuum.inventory"));
        assertTrue(tracker.isAnyDirty(player));
    }

    @Test
    void clearRemovesOnlyNamedModules() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();
        tracker.markDirty(player, "continuum.inventory");
        tracker.markDirty(player, "continuum.vitals");

        tracker.clear(player, Set.of("continuum.vitals"));

        assertTrue(tracker.isDirty(player, "continuum.inventory"));
        assertFalse(tracker.isDirty(player, "continuum.vitals"));
    }

    @Test
    void clearAllDropsEverythingForThePlayer() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();
        tracker.markAllDirty(player, Set.of("continuum.inventory", "continuum.vitals"));

        tracker.clearAll(player);

        assertFalse(tracker.isAnyDirty(player));
        assertEquals(Set.of(), tracker.dirtyModules(player));
    }

    @Test
    void markAllDirtyMarksEveryListedModule() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();

        tracker.markAllDirty(player, Set.of("continuum.inventory", "continuum.effects"));

        assertEquals(Set.of("continuum.inventory", "continuum.effects"), tracker.dirtyModules(player));
    }

    @Test
    void dirtySetOfOnePlayerDoesNotLeakIntoAnother() {
        DirtyTracker tracker = new DirtyTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        tracker.markDirty(first, "continuum.inventory");

        assertFalse(tracker.isDirty(second, "continuum.inventory"));
        assertFalse(tracker.isAnyDirty(second));
    }

    @Test
    void emptyOrBlankModuleIdsAreIgnored() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();

        tracker.markDirty(player, "");
        tracker.markDirty(player, "   ");

        assertFalse(tracker.isAnyDirty(player));
    }

    @Test
    void dirtyModulesReturnsADetachedSnapshot() {
        DirtyTracker tracker = new DirtyTracker();
        UUID player = UUID.randomUUID();
        tracker.markDirty(player, "continuum.vitals");

        Set<String> snapshot = tracker.dirtyModules(player);
        tracker.markDirty(player, "continuum.inventory");

        assertEquals(Set.of("continuum.vitals"), snapshot);
    }
}
