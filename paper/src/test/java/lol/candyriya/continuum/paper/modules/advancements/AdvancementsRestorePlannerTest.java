package lol.candyriya.continuum.paper.modules.advancements;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsRestorePlannerTest {

    private static final long CAPTURED_AT = 1_700_000_000_000L;

    private static AdvancementsPayload payload(AdvancementEntry... entries) {
        return new AdvancementsPayload(AdvancementsPayload.FORMAT, CAPTURED_AT, List.of(entries));
    }

    private static AdvancementEntry entry(String key, boolean done, String... awardedCriteria) {
        return new AdvancementEntry(key, List.of(awardedCriteria), done, Map.of());
    }

    private static AdvancementsRestorePlanner.TargetLookup fixedMap(
            Map<String, AdvancementsRestorePlanner.TargetAdvancement> byKey) {
        return key -> Optional.ofNullable(byKey.get(key));
    }

    private static AdvancementsRestorePlanner.TargetAdvancement target(boolean done, Set<String> criteria, String... awarded) {
        return new AdvancementsRestorePlanner.TargetAdvancement(criteria, Set.of(awarded), done);
    }

    @Nested
    class CriteriaDiffMath {

        @Test
        void nothingCapturedProducesAnEmptyPlan() {
            var plan = AdvancementsRestorePlanner.plan(payload(), key -> {
                throw new AssertionError("lookup must not be needed for an empty payload");
            });
            assertEquals(0, plan.decisions().size());
            assertEquals(0, plan.totalToAward());
            assertEquals(0, plan.totalUnknownCriteria());
            assertEquals(0, plan.count(AdvancementsRestorePlanner.Action.APPLY));
        }

        @Test
        void allCapturedCriteriaMissingOnTargetAreAwarded() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:story/mine_stone", false, "a", "b")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(false, Set.of("a", "b", "c")))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(List.of("a", "b"), decision.toAward());
            assertEquals(List.of(), decision.unknownCriteria());
            assertEquals(0, decision.alreadyAwarded());
        }

        @Test
        void alreadyAwardedCriteriaAreNeverReAwarded() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:story/mine_stone", false, "a", "b")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(false, Set.of("a", "b"), "a", "b"))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.NONE, decision.action());
            assertEquals(List.of(), decision.toAward());
            assertEquals(2, decision.alreadyAwarded());
        }

        @Test
        void onlyMissingCriteriaAreAwardedWhenPartiallyPresent() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:story/mine_stone", false, "a", "b", "c")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(false, Set.of("a", "b", "c", "d"), "a"))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(List.of("b", "c"), decision.toAward());
            assertEquals(1, decision.alreadyAwarded());
        }

        @Test
        void capturedCriteriaUnknownToTargetAreDroppedAndReported() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("my_mod:achievement", false, "keep_me", "gone_criterion", "also_gone")),
                    fixedMap(Map.of("my_mod:achievement",
                            target(false, Set.of("keep_me", "never_captured")))));
            var decision = plan.decisions().get(0);
            assertEquals(List.of("keep_me"), decision.toAward());
            assertEquals(List.of("also_gone", "gone_criterion"), decision.unknownCriteria());
        }

        @Test
        void allCapturedCriteriaUnknownToTargetAwardNothingButStillApplyTheRest() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("my_mod:achievement", false, "gone_a", "gone_b")),
                    fixedMap(Map.of("my_mod:achievement",
                            target(false, Set.of("kept")))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.NONE, decision.action());
            assertEquals(List.of(), decision.toAward());
            assertEquals(List.of("gone_a", "gone_b"), decision.unknownCriteria());
        }

        @Test
        void alreadyAwardedCountOnlyCountsKnownCriteria() {
            // 'gone' was awarded on this node but is no longer declared here: it must not
            // count as already-awarded work, nor appear anywhere in the plan
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("my_mod:achievement", false, "gone", "known")),
                    fixedMap(Map.of("my_mod:achievement",
                            target(false, Set.of("known"), "gone", "known"))));
            var decision = plan.decisions().get(0);
            assertEquals(1, decision.alreadyAwarded());
            // 'known' is already awarded on this node, 'gone' is not declared here:
            // nothing to award, the undeclared criterion is reported as unknown
            assertEquals(AdvancementsRestorePlanner.Action.NONE, decision.action());
            assertEquals(List.of(), decision.toAward());
            assertEquals(List.of("gone"), decision.unknownCriteria());
        }

        @Test
        void toAwardAndUnknownCriteriaAreSorted() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:x", false, "z", "a", "m", "gone_z", "gone_a")),
                    fixedMap(Map.of("minecraft:x",
                            target(false, Set.of("m", "a", "z")))));
            var decision = plan.decisions().get(0);
            assertEquals(List.of("a", "m", "z"), decision.toAward());
            assertEquals(List.of("gone_a", "gone_z"), decision.unknownCriteria());
        }
    }

    @Nested
    class UnknownAdvancementPolicy {

        @Test
        void advancementUnknownToThisServerIsSkippedNotFatal() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("modpack:not_here", false, "x")),
                    key -> Optional.empty());
            assertEquals(1, plan.count(AdvancementsRestorePlanner.Action.SKIP_UNKNOWN_ADVANCEMENT));
            assertEquals(0, plan.totalToAward());
        }

        @Test
        void unknownAdvancementDoesNotAffectKnownOnes() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(
                            entry("modpack:not_here", false, "x"),
                            entry("minecraft:story/mine_stone", false, "y")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(false, Set.of("y")))));
            var byKey = plan.decisions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AdvancementsRestorePlanner.Decision::key,
                            AdvancementsRestorePlanner.Decision::action));
            assertEquals(AdvancementsRestorePlanner.Action.SKIP_UNKNOWN_ADVANCEMENT, byKey.get("modpack:not_here"));
            assertEquals(AdvancementsRestorePlanner.Action.APPLY, byKey.get("minecraft:story/mine_stone"));
        }

        @Test
        void malformedCapturedKeyIsSkippedWithoutConsultingTheLookup() {
            var lookupCalls = new AtomicInteger();
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("Not A Key!", false, "x")),
                    key -> {
                        lookupCalls.incrementAndGet();
                        return Optional.empty();
                    });
            assertEquals(AdvancementsRestorePlanner.Action.SKIP_MALFORMED_KEY,
                    plan.decisions().get(0).action());
            assertEquals(0, lookupCalls.get(), "lookup must never be consulted for a malformed key");
        }

        @Test
        void lookupThatThrowsIsTreatedAsUnknownInsteadOfAbortingTheRestore() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(
                            entry("my_mod:explode", false, "x"),
                            entry("minecraft:story/mine_stone", false, "y")),
                    key -> {
                        if (key.equals("my_mod:explode")) {
                            throw new IllegalStateException("registry hiccup");
                        }
                        return Optional.of(target(false, Set.of("y")));
                    });
            var byKey = plan.decisions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            AdvancementsRestorePlanner.Decision::key,
                            AdvancementsRestorePlanner.Decision::action));
            assertEquals(AdvancementsRestorePlanner.Action.SKIP_UNKNOWN_ADVANCEMENT, byKey.get("my_mod:explode"));
            assertEquals(AdvancementsRestorePlanner.Action.APPLY, byKey.get("minecraft:story/mine_stone"));
        }

        @Test
        void doneStateIsNeverForceRestoredWhenTargetIsDoneAndNothingIsOwed() {
            // capture says done; target declares everything captured and it is all awarded,
            // target itself is done: nothing to do, and completion is never written directly
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:story/mine_stone", true, "a", "b")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(true, Set.of("a", "b"), "a", "b"))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.NONE, decision.action());
            assertTrue(decision.doneCaptured());
            assertTrue(decision.targetDone());
        }

        @Test
        void doneCapturedButTargetNotDoneStaysUntouched() {
            // the capture claims done but this node never awarded everything: award-only
            // forbids forcing completion, so the plan is NONE and the module logs a warning
            var plan = AdvancementsRestorePlanner.plan(
                    payload(entry("minecraft:story/mine_stone", true, "a", "b")),
                    fixedMap(Map.of("minecraft:story/mine_stone",
                            target(false, Set.of("a", "b"), "a", "b"))));
            var decision = plan.decisions().get(0);
            assertEquals(AdvancementsRestorePlanner.Action.NONE, decision.action());
            assertTrue(decision.doneCaptured());
            assertFalse(decision.targetDone());
        }
    }

    @Nested
    class DeterministicOrdering {

        @Test
        void decisionsAreSortedByAdvancementKeyNotPayloadOrder() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(
                            entry("minecraft:zeta", false, "c"),
                            entry("minecraft:alpha", false, "a"),
                            entry("minecraft:middle", false, "b")),
                    fixedMap(Map.of(
                            "minecraft:zeta", target(false, Set.of("c")),
                            "minecraft:alpha", target(false, Set.of("a")),
                            "minecraft:middle", target(false, Set.of("b")))));
            assertEquals(List.of("minecraft:alpha", "minecraft:middle", "minecraft:zeta"),
                    plan.decisions().stream().map(AdvancementsRestorePlanner.Decision::key).toList());
        }

        @Test
        void repeatedPlanningOfTheSamePayloadYieldsEqualDecisions() {
            var payload = payload(
                    entry("minecraft:zeta", false, "c"),
                    entry("minecraft:alpha", false, "a"));
            var lookup = fixedMap(Map.of(
                    "minecraft:zeta", target(false, Set.of("c")),
                    "minecraft:alpha", target(false, Set.of("a"))));
            var first = AdvancementsRestorePlanner.plan(payload, lookup);
            var second = AdvancementsRestorePlanner.plan(payload, lookup);
            assertEquals(first, second);
        }
    }

    @Nested
    class PlanTotals {

        @Test
        void totalsAddUpAcrossDecisions() {
            var plan = AdvancementsRestorePlanner.plan(
                    payload(
                            entry("minecraft:apply_me", false, "a", "gone"),
                            entry("minecraft:none_here", false, "known"),
                            entry("modpack:unknown", false, "zzz")),
                    key -> switch (key) {
                        case "minecraft:apply_me" -> Optional.of(target(false, Set.of("a")));
                        case "minecraft:none_here" -> Optional.of(target(false, Set.of("known"), "known"));
                        default -> Optional.empty();
                    });
            assertEquals(1, plan.totalToAward());
            assertEquals(1, plan.totalUnknownCriteria());
            assertEquals(1, plan.count(AdvancementsRestorePlanner.Action.APPLY));
            assertEquals(1, plan.count(AdvancementsRestorePlanner.Action.NONE));
            assertEquals(1, plan.count(AdvancementsRestorePlanner.Action.SKIP_UNKNOWN_ADVANCEMENT));
        }
    }
}
