package lol.candyriya.continuum.paper.modules.statistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-style coverage for the restore-side statistics reconciliation:
 * the merged counter must always equal {@code max(snapshot, local)} — never
 * lower, never lose a count.
 *
 * <p>Exhaustive over the small grid 0..511 (512 x 512 pairs, every one
 * checked) plus a seeded sample of a few thousand pairs across the full
 * non-negative int range, including the interesting edges 0, 1, 2,
 * 2^31-2 and 2^31-1. The planner is the real production code path here;
 * the slot is a String so no platform stat catalog is needed.</p>
 */
class StatisticsReconciliationPropertyTest {

    private static final int GRID_EDGE = 511;
    private static final long SEED = 0x57A7L;
    private static final int INT_MAX = 0x7FFF_FFFF; // 2^31 - 1

    private static long merged(long snapshot, long local) {
        var captured = List.of(new StatisticsPayload.Entry("untyped:test", snapshot));
        var plan = StatisticsRestorePlanner.plan(
                captured,
                key -> Optional.of("SLOT"),
                slot -> local);
        return plan.writes().isEmpty() ? local : plan.writes().getFirst().value();
    }

    @Test
    void exhaustiveSmallGridMatchesMaxExactly() {
        long checked = 0;
        for (int snapshot = 0; snapshot <= GRID_EDGE; snapshot++) {
            for (int local = 0; local <= GRID_EDGE; local++) {
                long merged = merged(snapshot, local);

                assertEquals(Math.max((long) snapshot, (long) local), merged,
                        "snapshot=" + snapshot + " local=" + local);
                assertTrue(merged >= snapshot, "result below the snapshot: snapshot=" + snapshot + " local=" + local);
                assertTrue(merged >= local, "result below the local counter: snapshot=" + snapshot + " local=" + local);
                checked++;
            }
        }
        assertEquals((long) (GRID_EDGE + 1) * (GRID_EDGE + 1), checked);
    }

    @Test
    void seededSamplesAcrossTheFullIntRangeMatchMaxExactly() {
        Random random = new Random(SEED);
        int[] edges = {0, 1, 2, INT_MAX - 1, INT_MAX};

        long checked = 0;
        // every pair of interesting edges first — these are where off-by-one lives
        for (int snapshot : edges) {
            for (int local : edges) {
                assertMerge(snapshot, local);
                checked++;
            }
        }
        for (int i = 0; i < 3_000; i++) {
            assertMerge(random.nextInt(0, INT_MAX), random.nextInt(0, INT_MAX));
            checked++;
        }
        // not strictly required by the property, but the loop must actually have run
        assertEquals(25 + 3_000L, checked);
    }

    @Test
    void snapshotEqualToLocalOrLowerNeverWrites() {
        // captured == local and captured < local must both end with zero writes;
        // the local counter stands (cumulative-counter semantics)
        for (int local : new int[]{0, 1, 2, INT_MAX - 1, INT_MAX}) {
            for (int snapshot : new int[]{0, 1, 2, INT_MAX - 1, INT_MAX}) {
                var captured = List.of(new StatisticsPayload.Entry("untyped:test", snapshot));
                var plan = StatisticsRestorePlanner.plan(
                        captured,
                        key -> Optional.of("SLOT"),
                        slot -> (long) local);
                if (snapshot > local) {
                    assertEquals(1, plan.writes().size(), "snapshot>local must schedule a catch-up write");
                } else {
                    assertEquals(0, plan.writes().size(), "snapshot<=local must not write");
                    assertEquals(1, plan.keptAhead());
                }
            }
        }
    }

    @Test
    void reconciliationIsIdempotentUnderReplay() {
        // applying the plan twice leaves the same state: the second pass sees
        // local = merged and must find nothing to do
        Random random = new Random(SEED);
        for (int i = 0; i < 500; i++) {
            long snapshot = random.nextInt(0, INT_MAX);
            long local = random.nextInt(0, INT_MAX);
            long first = merged(snapshot, local);
            long second = merged(snapshot, first);
            assertEquals(first, second, "replaying against the merged state must be a no-op");
        }
    }

    private static void assertMerge(int snapshot, int local) {
        long merged = merged(snapshot, local);
        assertEquals(Math.max((long) snapshot, (long) local), merged,
                "snapshot=" + snapshot + " local=" + local);
        assertTrue(merged >= snapshot && merged >= local,
                "result below an input: snapshot=" + snapshot + " local=" + local);
    }
}
