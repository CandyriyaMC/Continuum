package lol.candyriya.continuum.paper.modules.statistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation rules of the restore planner, on string slots so no live
 * server (or even a Bukkit enum) is needed.
 */
class StatisticsRestorePlannerTest {

    private static StatisticsPayload.Entry entry(String key, long value) {
        return new StatisticsPayload.Entry(key, value);
    }

    @Test
    void capturedAheadOfLocalSchedulesAnAbsoluteWrite() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:deaths", 100L)),
                Optional::of,
                slot -> 7L);

        assertEquals(1, plan.writes().size());
        assertEquals("untyped:deaths", plan.writes().get(0).slot());
        assertEquals(100L, plan.writes().get(0).value());
        assertEquals(0, plan.keptAhead());
        assertTrue(plan.skippedUnknown().isEmpty());
        assertTrue(plan.unreadable().isEmpty());
    }

    @Test
    void localAheadOfSnapshotWins() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:deaths", 50L)),
                Optional::of,
                slot -> 80L);

        assertTrue(plan.writes().isEmpty());
        assertEquals(1, plan.keptAhead());
    }

    @Test
    void equalCountersAreKeptLocalNoWrite() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:deaths", 80L)),
                Optional::of,
                slot -> 80L);

        assertTrue(plan.writes().isEmpty());
        assertEquals(1, plan.keptAhead());
    }

    @Test
    void unknownKeysAreSkippedNeverWritten() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:some_future_stat", 10L), entry("untyped:deaths", 10L)),
                key -> key.equals("untyped:deaths") ? Optional.of(key) : Optional.empty(),
                slot -> 0L);

        assertEquals(List.of("untyped:some_future_stat"), plan.skippedUnknown());
        assertEquals(1, plan.writes().size());
        assertEquals("untyped:deaths", plan.writes().get(0).slot());
    }

    @Test
    void refusedLocalReadsAreLeftUntouched() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:deaths", 100L), entry("untyped:walk_one_cm", 5L)),
                Optional::of,
                slot -> {
                    if (slot.equals("untyped:deaths")) {
                        throw new IllegalStateException("offline stats unavailable");
                    }
                    return 1L;
                });

        assertEquals(List.of("untyped:deaths"), plan.unreadable());
        assertEquals(1, plan.writes().size());
        assertEquals("untyped:walk_one_cm", plan.writes().get(0).slot());
    }

    @Test
    void writesPreservePayloadOrder() {
        List<StatisticsPayload.Entry> captured = List.of(
                entry("block:mine_block:stone", 10L),
                entry("untyped:deaths", 10L),
                entry("item:use_item:diamond_sword", 10L));

        var plan = StatisticsRestorePlanner.plan(captured, Optional::of, slot -> 0L);

        assertEquals(List.of(
                "block:mine_block:stone",
                "untyped:deaths",
                "item:use_item:diamond_sword"), plan.writes().stream().map(StatisticsRestorePlanner.StatWrite::slot).toList());
    }

    @Test
    void mixedScenarioComputesTheToApplySet() {
        // the classic diff: one cell raises, one stays, one is unknown, one unreadable
        var plan = StatisticsRestorePlanner.plan(
                List.of(
                        entry("untyped:deaths", 100L),       // local 90 → write 100
                        entry("untyped:walk_one_cm", 100L),  // local 5000 → keep
                        entry("item:use_item:unobtainium", 1L),
                        entry("entity:kill_entity:zombie", 42L)),
                key -> "item:use_item:unobtainium".equals(key) ? Optional.empty() : Optional.of(key),
                slot -> {
                    if ("untyped:walk_one_cm".equals(slot)) {
                        return 5000L;
                    }
                    if ("entity:kill_entity:zombie".equals(slot)) {
                        throw new IllegalStateException("entity stats unreadable");
                    }
                    return 90L;
                });

        assertEquals(List.of("untyped:deaths"), plan.writes().stream().map(StatisticsRestorePlanner.StatWrite::slot).toList());
        assertEquals(1, plan.keptAhead());
        assertEquals(List.of("item:use_item:unobtainium"), plan.skippedUnknown());
        assertEquals(List.of("entity:kill_entity:zombie"), plan.unreadable());
    }

    @Test
    void emptyCapturedSetPlansNoWork() {
        var plan = StatisticsRestorePlanner.plan(List.of(), Optional::of, slot -> {
            throw new AssertionError("local read must not run for an empty snapshot");
        });

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.keptAhead());
    }

    @Test
    void fullAheadSnapshotWritesNothing() {
        var plan = StatisticsRestorePlanner.plan(
                List.of(entry("untyped:deaths", 1L), entry("untyped:sneak_time", 2L)),
                Optional::of,
                slot -> 999L);

        assertTrue(plan.isEmpty());
        assertEquals(2, plan.keptAhead());
    }
}
