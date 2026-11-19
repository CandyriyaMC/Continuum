package lol.candyriya.continuum.paper.modules.effects;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectsRestorePlannerTest {

    private static final long CAPTURED_AT = 1_700_000_000_000L;

    /** Fixed namespace map: key -> isInstant. Anything absent is unknown to the server. */
    private static EffectTypeLookup fixedMap(Map<String, Boolean> instantByKey) {
        return key -> {
            Boolean instant = instantByKey.get(key);
            return instant == null ? Optional.empty() : Optional.of(new EffectTypeLookup.Info(instant));
        };
    }

    private static final EffectTypeLookup SPEED_AND_INSTANT_HEALTH =
            fixedMap(Map.of("minecraft:speed", false, "minecraft:instant_health", true));

    private static EffectsPayload payload(long capturedAt, EffectEntry... entries) {
        return new EffectsPayload(EffectsPayload.FORMAT, capturedAt, List.of(entries));
    }

    private static EffectEntry entry(String type, int duration, int amplifier) {
        return new EffectEntry(type, duration, amplifier, false, true, true, false);
    }

    @Nested
    class RemainingDurationMath {

        @Test
        void noTimeElapsedRestoresFullDuration() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 600, 0)), CAPTURED_AT,
                    SPEED_AND_INSTANT_HEALTH);
            assertEquals(1, plan.decisions().size());
            var decision = plan.decisions().get(0);
            assertEquals(EffectsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(600, decision.remainingTicks());
            assertFalse(plan.clockSkewed());
        }

        @Test
        void subtractsElapsedWallClockInTicks() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 600, 0)), CAPTURED_AT + 10_000,
                    SPEED_AND_INSTANT_HEALTH);
            // 10_000 ms = 200 ticks elapsed; 600 - 200 = 400 left
            assertEquals(400, plan.decisions().get(0).remainingTicks());
        }

        @Test
        void subTickElapsedFloorsToWholeTicks() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 100, 0)), CAPTURED_AT + 1_049,
                    SPEED_AND_INSTANT_HEALTH);
            // 1_049 ms / 50 = 20 whole ticks
            assertEquals(80, plan.decisions().get(0).remainingTicks());
        }

        @Test
        void exactlyZeroRemainingIsSkippedAsExpired() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 200, 0)), CAPTURED_AT + 10_000,
                    SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_EXPIRED, plan.decisions().get(0).action());
            assertEquals(0, plan.decisions().get(0).remainingTicks());
        }

        @Test
        void oneTickRemainingIsApplied() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 201, 0)), CAPTURED_AT + 10_000,
                    SPEED_AND_INSTANT_HEALTH);
            var decision = plan.decisions().get(0);
            assertEquals(EffectsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(1, decision.remainingTicks());
        }

        @Test
        void effectThatExpiredWhileAwayIsNotReapplied() {
            // captured 30 minutes ago with a 5 second effect: long gone
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 100, 0)), CAPTURED_AT + 30 * 60_000L,
                    SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_EXPIRED, plan.decisions().get(0).action());
            assertTrue(plan.decisions().get(0).remainingTicks() <= 0);
        }

        @Test
        void hugeElapsedDoesNotOverflow() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 1_000_000, 0)),
                    CAPTURED_AT + Long.MAX_VALUE / 2,
                    SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_EXPIRED, plan.decisions().get(0).action());
        }

        @Test
        void infiniteEffectSurvivesArbitraryAbsence() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", EffectsPayload.INFINITE_DURATION_TICKS, 1)),
                    CAPTURED_AT + 365L * 24 * 60 * 60_000,
                    SPEED_AND_INSTANT_HEALTH);
            var decision = plan.decisions().get(0);
            assertEquals(EffectsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(EffectsPayload.INFINITE_DURATION_TICKS, decision.remainingTicks());
        }

        @Test
        void captureTimestampInTheFutureRestoresFullDurationAndReportsSkew() {
            var plan = EffectsRestorePlanner.plan(
                    payload(CAPTURED_AT, entry("minecraft:speed", 600, 0)), CAPTURED_AT - 60_000,
                    SPEED_AND_INSTANT_HEALTH);
            assertTrue(plan.clockSkewed());
            var decision = plan.decisions().get(0);
            assertEquals(EffectsRestorePlanner.Action.APPLY, decision.action());
            assertEquals(600, decision.remainingTicks());
        }

        @Test
        void mixedEffectsOnlyApplyTheSurvivors() {
            var payload = payload(CAPTURED_AT,
                    entry("minecraft:speed", 200, 0),       // expires exactly at now
                    entry("minecraft:night_vision", 10_000, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT + 10_000,
                    fixedMap(Map.of("minecraft:speed", false, "minecraft:night_vision", false)));
            assertEquals(2, plan.decisions().size());
            var byKey = plan.decisions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            EffectsRestorePlanner.Decision::typeKey,
                            EffectsRestorePlanner.Decision::action));
            assertEquals(EffectsRestorePlanner.Action.SKIP_EXPIRED, byKey.get("minecraft:speed"));
            assertEquals(EffectsRestorePlanner.Action.APPLY, byKey.get("minecraft:night_vision"));
        }
    }

    @Nested
    class DuplicateAndClampRules {

        @Test
        void duplicateTypeKeysKeepTheLastOccurrence() {
            var payload = payload(CAPTURED_AT,
                    entry("minecraft:speed", 100, 0),
                    entry("minecraft:speed", 500, 1));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(1, plan.decisions().size());
            assertEquals(1, plan.duplicatesDropped());
            var decision = plan.decisions().get(0);
            assertEquals(500, decision.remainingTicks());
            assertEquals(1, decision.amplifier());
        }

        @Test
        void decisionsAreSortedByTypeKeyForDeterminism() {
            var payload = payload(CAPTURED_AT,
                    entry("minecraft:weakness", 100, 0),
                    entry("minecraft:speed", 100, 0),
                    entry("minecraft:night_vision", 100, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT,
                    fixedMap(Map.of("minecraft:weakness", false, "minecraft:speed", false, "minecraft:night_vision", false)));
            assertEquals(List.of("minecraft:night_vision", "minecraft:speed", "minecraft:weakness"),
                    plan.decisions().stream().map(EffectsRestorePlanner.Decision::typeKey).toList());
        }

        @Test
        void amplifierIsClampedToByteRangeAtPlanTime() {
            var payload = payload(CAPTURED_AT, entry("minecraft:speed", 100, 300));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(255, plan.decisions().get(0).amplifier());
        }

        @Test
        void negativeAmplifierIsClampedToZero() {
            var payload = payload(CAPTURED_AT, entry("minecraft:speed", 100, -7));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(0, plan.decisions().get(0).amplifier());
        }
    }

    @Nested
    class UnknownAndInstantTypes {

        @Test
        void unknownTypeIsSkippedAndDoesNotAffectOtherEntries() {
            var payload = payload(CAPTURED_AT,
                    entry("winter:flashfreeze", 100, 0),
                    entry("minecraft:speed", 100, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            var byKey = plan.decisions().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            EffectsRestorePlanner.Decision::typeKey,
                            EffectsRestorePlanner.Decision::action));
            assertEquals(EffectsRestorePlanner.Action.SKIP_UNKNOWN, byKey.get("winter:flashfreeze"));
            assertEquals(EffectsRestorePlanner.Action.APPLY, byKey.get("minecraft:speed"));
            assertEquals(1, plan.count(EffectsRestorePlanner.Action.SKIP_UNKNOWN));
        }

        @Test
        void malformedTypeKeyIsTreatedAsUnknown() {
            var payload = payload(CAPTURED_AT, entry("not a key at all", 100, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_UNKNOWN, plan.decisions().get(0).action());
        }

        @Test
        void instantTypeFromSnapshotFlagIsSkipped() {
            // server knows the type, but the entry claims it is instant (e.g. recorded that way at capture)
            var payload = payload(CAPTURED_AT,
                    new EffectEntry("minecraft:speed", 100, 0, false, true, true, true));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_INSTANT, plan.decisions().get(0).action());
        }

        @Test
        void instantTypeKnownToServerIsSkipped() {
            var payload = payload(CAPTURED_AT, entry("minecraft:instant_health", 1, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_INSTANT, plan.decisions().get(0).action());
        }

        @Test
        void unknownInstantKeyIsSkippedAsUnknownNotAsInstant() {
            // unknown wins over instant: we cannot know it is instant without the type
            var payload = payload(CAPTURED_AT, entry("winter:flashfreeze", 100, 0));
            var plan = EffectsRestorePlanner.plan(payload, CAPTURED_AT, SPEED_AND_INSTANT_HEALTH);
            assertEquals(EffectsRestorePlanner.Action.SKIP_UNKNOWN, plan.decisions().get(0).action());
        }
    }

    @Nested
    class CaptureNormalization {

        @Test
        void amplifierBelowRangeClampsToZero() {
            assertEquals(0, EffectsRestorePlanner.clampAmplifier(Integer.MIN_VALUE));
        }

        @Test
        void amplifierAboveRangeClampsTo255() {
            assertEquals(255, EffectsRestorePlanner.clampAmplifier(Integer.MAX_VALUE));
            assertEquals(255, EffectsRestorePlanner.clampAmplifier(256));
            assertEquals(255, EffectsRestorePlanner.clampAmplifier(255));
        }

        @Test
        void infiniteDurationMarkerStaysInfinite() {
            assertEquals(EffectsPayload.INFINITE_DURATION_TICKS,
                    EffectsRestorePlanner.normalizeDuration(EffectsPayload.INFINITE_DURATION_TICKS));
        }

        @Test
        void zeroDurationIsDropped() {
            assertNull(EffectsRestorePlanner.normalizeDuration(0));
        }

        @Test
        void meaninglessNegativeDurationIsDropped() {
            assertNull(EffectsRestorePlanner.normalizeDuration(-2));
            assertNull(EffectsRestorePlanner.normalizeDuration(-100));
        }

        @Test
        void positiveDurationPassesThrough() {
            assertEquals(123, EffectsRestorePlanner.normalizeDuration(123));
        }

        @Test
        void dedupeLastWinsKeepsOrderAndDropsEarlierCopies() {
            var entries = List.of(
                    entry("minecraft:speed", 1, 0),
                    entry("minecraft:weakness", 2, 0),
                    entry("minecraft:speed", 3, 0));
            var deduped = EffectsRestorePlanner.dedupeLastWins(entries);
            assertEquals(2, deduped.size());
            assertEquals(List.of("minecraft:speed", "minecraft:weakness"),
                    deduped.stream().map(EffectEntry::type).toList());
            assertEquals(3, deduped.get(0).durationTicks());
        }
    }
}
