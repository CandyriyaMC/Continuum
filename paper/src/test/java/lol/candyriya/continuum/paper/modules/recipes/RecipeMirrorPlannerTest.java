package lol.candyriya.continuum.paper.modules.recipes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeMirrorPlannerTest {

    /** Fixed server knowledge: anything absent from the set is unknown to the target. */
    private static java.util.function.Predicate<String> knownTo(String... keys) {
        Set<String> known = Set.of(keys);
        return known::contains;
    }

    private static final java.util.function.Predicate<String> ALL_KNOWN = key -> true;

    @Test
    void fullyInSyncBookYieldsNoChanges() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:apple", "minecraft:stone"),
                Set.of("minecraft:stone", "minecraft:apple"),
                ALL_KNOWN);

        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of(), plan.toForget());
        assertEquals(List.of(), plan.unknownToTarget());
        assertEquals(0, plan.duplicatesCollapsed());
        assertFalse(plan.changed());
    }

    @Test
    void missingDiscoveriesArePlannedSorted() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:stone", "minecraft:apple", "minecraft:bread"),
                Set.of(),
                ALL_KNOWN);

        assertEquals(List.of("minecraft:apple", "minecraft:bread", "minecraft:stone"), plan.toDiscover());
        assertEquals(List.of(), plan.toForget());
        assertTrue(plan.changed());
    }

    @Test
    void extraLocalDiscoveriesArePlannedForForget() {
        // mirror semantics: locally known but absent from the capture — forget it
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:apple"),
                Set.of("minecraft:apple", "minecraft:cake", "minecraft:bed"),
                ALL_KNOWN);

        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of("minecraft:bed", "minecraft:cake"), plan.toForget());
        assertTrue(plan.changed());
    }

    @Test
    void mixedDiscoverAndForgetInOnePlan() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:bread", "minecraft:cookie"),
                Set.of("minecraft:bread", "minecraft:cake"),
                ALL_KNOWN);

        assertEquals(List.of("minecraft:cookie"), plan.toDiscover());
        assertEquals(List.of("minecraft:cake"), plan.toForget());
        assertTrue(plan.changed());
    }

    @Test
    void capturedKeysUnknownToTargetAreSkippedWithReport() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:stone", "technic:copper_gear"),
                Set.of(),
                knownTo("minecraft:stone"));

        assertEquals(List.of("minecraft:stone"), plan.toDiscover());
        assertEquals(List.of("technic:copper_gear"), plan.unknownToTarget());
        assertEquals(List.of(), plan.toForget());
    }

    @Test
    void localKeysUnknownToTargetAreSkippedNotForgotten() {
        // the server doesn't ship "technic:copper_gear" — the book cannot hold it anyway
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of(),
                Set.of("technic:copper_gear", "minecraft:stone"),
                knownTo("minecraft:stone"));

        assertEquals(List.of("minecraft:stone"), plan.toForget());
        assertEquals(List.of("technic:copper_gear"), plan.forgetSkippedUnknown());
    }

    @Test
    void capturedButUnknownLocalKeyIsNotForgotten() {
        // capture lists the key, target doesn't ship it, player has it (stale book entry from
        // when the pack was installed) — leave it alone, report it, don't touch the book
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("technic:copper_gear"),
                Set.of("technic:copper_gear"),
                knownTo());

        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of(), plan.toForget());
        assertEquals(List.of("technic:copper_gear"), plan.unknownToTarget());
        assertEquals(List.of("technic:copper_gear"), plan.forgetSkippedUnknown());
        assertFalse(plan.changed());
    }

    @Test
    void capturedLocalAndTargetMismatch() {
        // captured says learn "technic:copper_gear", target doesn't know it;
        // locally discovered "minecraft:cake" is not in the capture -> forget it
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("technic:copper_gear"),
                Set.of("minecraft:cake"),
                knownTo("minecraft:cake"));

        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of("minecraft:cake"), plan.toForget());
        assertEquals(List.of("technic:copper_gear"), plan.unknownToTarget());
    }

    @Test
    void emptyCaptureForgetsEverythingTheTargetKnows() {
        // exact-replica mirror: a captured-empty book means the player should know nothing
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of(),
                Set.of("minecraft:cake", "minecraft:bed", "technic:copper_gear"),
                knownTo("minecraft:cake", "minecraft:bed"));

        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of("minecraft:bed", "minecraft:cake"), plan.toForget());
        assertEquals(List.of("technic:copper_gear"), plan.forgetSkippedUnknown());
        assertTrue(plan.changed());
    }

    @Test
    void emptyCaptureAndEmptyLocalIsNoOp() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(List.of(), Set.of(), ALL_KNOWN);

        assertFalse(plan.changed());
        assertEquals(List.of(), plan.toDiscover());
        assertEquals(List.of(), plan.toForget());
    }

    @Test
    void lookupPredicateThrowingPropagates() {
        // a broken lookup is a bug, not something to silently route around
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> RecipeMirrorPlanner.plan(List.of("minecraft:stone"), Set.of(), key -> {
                    throw new IllegalStateException("recipe map unavailable");
                }));
    }

    @Test
    void duplicatesInCaptureAreCollapsedAndCounted() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:stone", "minecraft:apple", "minecraft:stone", "minecraft:stone"),
                Set.of(),
                ALL_KNOWN);

        assertEquals(List.of("minecraft:apple", "minecraft:stone"), plan.toDiscover());
        assertEquals(2, plan.duplicatesCollapsed());
    }

    @Test
    void invalidCapturedEntriesAreReportedNotApplied() {
        List<String> captured = new ArrayList<>();
        captured.add("minecraft:stone");
        captured.add("garbage key");
        captured.add(null);
        captured.add("technic:Copper_Gear"); // uppercase — invalid

        MirrorPlan plan = RecipeMirrorPlanner.plan(captured, Set.of(), ALL_KNOWN);

        assertEquals(List.of("minecraft:stone"), plan.toDiscover());
        assertEquals(3, plan.invalidCaptured().size());
        assertTrue(plan.invalidCaptured().contains("garbage key"));
        assertTrue(plan.invalidCaptured().contains("<null>"));
        assertTrue(plan.invalidCaptured().contains("technic:Copper_Gear"));
    }

    @Test
    void duplicateCaptureEntriesCollapseToTheSingleLocalKey() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:stone", "minecraft:stone"),
                Set.of("minecraft:stone"),
                ALL_KNOWN);

        assertFalse(plan.changed());
        assertEquals(1, plan.duplicatesCollapsed());
    }

    @Test
    void outputsAreSortedRegardlessOfInputOrder() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(
                List.of("minecraft:stone", "minecraft:apple", "minecraft:bread"),
                Set.of("minecraft:zoo", "minecraft:apple", "minecraft:bed"),
                ALL_KNOWN);

        assertEquals(List.of("minecraft:bread", "minecraft:stone"), plan.toDiscover());
        assertEquals(List.of("minecraft:bed", "minecraft:zoo"), plan.toForget());
        assertEquals(plan.toDiscover(), plan.toDiscover().stream().sorted().toList());
        assertEquals(plan.toForget(), plan.toForget().stream().sorted().toList());
    }

    @Test
    void nullLocalSetIsTolerated() {
        MirrorPlan plan = RecipeMirrorPlanner.plan(List.of("minecraft:stone"), null, ALL_KNOWN);

        assertEquals(List.of("minecraft:stone"), plan.toDiscover());
        assertEquals(List.of(), plan.toForget());
    }

    @Test
    void multipleLocalExtrasAreAllPlannedForForget() {
        Set<String> local = new HashSet<>();
        local.add("minecraft:cake");
        local.add("minecraft:bed");
        MirrorPlan plan = RecipeMirrorPlanner.plan(List.of(), local, ALL_KNOWN);

        assertEquals(List.of("minecraft:bed", "minecraft:cake"), plan.toForget());
    }
}
