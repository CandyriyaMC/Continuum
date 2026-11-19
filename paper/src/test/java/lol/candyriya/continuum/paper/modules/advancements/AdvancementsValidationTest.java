package lol.candyriya.continuum.paper.modules.advancements;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsValidationTest {

    private static AdvancementEntry example(String key) {
        return new AdvancementEntry(key, List.of("criterion"), false, Map.of("criterion", 1_700_000_000_000L));
    }

    private static AdvancementsPayload payload(int format, long capturedAt, List<AdvancementEntry> entries) {
        return new AdvancementsPayload(format, capturedAt, entries);
    }

    @Test
    void currentFormatIsAccepted() {
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1_700_000_000_000L, List.of(example("minecraft:story/mine_stone"))));
        assertTrue(result.valid());
    }

    @Test
    void emptyAdvancementsListIsAccepted() {
        // a player with zero progress captures an empty list; that is a fine payload
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 0L, List.of()));
        assertTrue(result.valid());
    }

    @Test
    void olderFormatIsRejected() {
        ValidationResult result = AdvancementsValidation.check(payload(0, 1_700_000_000_000L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"));
    }

    @Test
    void newerFormatIsRejected() {
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT + 1, 1_700_000_000_000L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"));
    }

    @Test
    void negativeCaptureTimestampIsRejected() {
        ValidationResult result = AdvancementsValidation.check(payload(AdvancementsPayload.FORMAT, -1L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("captured_at_epoch_milli"));
    }

    @Test
    void zeroCaptureTimestampIsAccepted() {
        ValidationResult result = AdvancementsValidation.check(payload(AdvancementsPayload.FORMAT, 0L, List.of()));
        assertTrue(result.valid());
    }

    @Test
    void exactlyMaxAdvancementsIsAccepted() {
        var entries = new ArrayList<AdvancementEntry>();
        for (int i = 0; i < AdvancementsPayload.MAX_ADVANCEMENTS; i++) {
            entries.add(example("minecraft:gen/adv_" + i));
        }
        assertTrue(AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, entries)).valid());
    }

    @Test
    void beyondMaxAdvancementsIsRejected() {
        var entries = new ArrayList<AdvancementEntry>();
        for (int i = 0; i <= AdvancementsPayload.MAX_ADVANCEMENTS; i++) {
            entries.add(example("minecraft:gen/adv_" + i));
        }
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, entries));
        assertFalse(result.valid());
        assertTrue(result.reason().contains(String.valueOf(AdvancementsPayload.MAX_ADVANCEMENTS)));
    }

    @Test
    void exactlyMaxCriteriaPerAdvancementIsAccepted() {
        var criteria = new ArrayList<String>();
        for (int i = 0; i < AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT; i++) {
            criteria.add("criterion_" + i);
        }
        var entry = new AdvancementEntry("minecraft:story/mine_stone", criteria, false, Map.of());
        assertTrue(AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry))).valid());
    }

    @Test
    void beyondMaxCriteriaPerAdvancementIsRejected() {
        var criteria = new ArrayList<String>();
        for (int i = 0; i <= AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT; i++) {
            criteria.add("criterion_" + i);
        }
        var entry = new AdvancementEntry("minecraft:story/mine_stone", criteria, false, Map.of());
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains(String.valueOf(AdvancementsPayload.MAX_CRITERIA_PER_ADVANCEMENT)));
    }

    @Test
    void invalidEntryKeyIsRejected() {
        var entry = example("not a key at all");
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("not a key at all") || result.reason().contains("invalid key"));
    }

    @Test
    void blankAwardedCriterionIsRejected() {
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("fine", " "), false, Map.of());
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("blank"));
    }

    @Test
    void negativeAwardedAtValueIsRejected() {
        var awardedAt = new TreeMap<String, Long>();
        awardedAt.put("criterion", -1L);
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("criterion"), false, awardedAt);
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry)));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("award") || result.reason().contains("awarded_at"));
    }

    @Test
    void zeroAwardedAtValueIsAccepted() {
        var awardedAt = new TreeMap<String, Long>();
        awardedAt.put("criterion", 0L);
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("criterion"), false, awardedAt);
        assertTrue(AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry))).valid());
    }

    @Test
    void awardedAtCriterionAbsentFromAwardedCriteriaIsToleratedWithAWarning() {
        // criteria subset issues are warn-only: restore never reads awarded_at, so a
        // snapshot with this inconsistency must still apply
        var awardedAt = new TreeMap<String, Long>();
        awardedAt.put("ghost_criterion", 1_700_000_000_000L);
        var entry = new AdvancementEntry("minecraft:story/mine_stone", List.of("criterion"), false, awardedAt);
        ValidationResult result = AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry)));
        assertTrue(result.valid(), "subset issues must not reject the payload: " + result.reason());
    }

    @Test
    void duplicateCriteriaAreAccepted() {
        // the entry normalizes duplicates away (sorted, deduplicated), so a stored
        // payload carrying them applies cleanly; the restore math is set-based anyway
        var entry = new AdvancementEntry("minecraft:story/mine_stone",
                List.of("criterion", "criterion", "criterion"), false, Map.of());
        assertTrue(AdvancementsValidation.check(
                payload(AdvancementsPayload.FORMAT, 1L, List.of(entry))).valid());
    }

    @Test
    void invalidReasonIsNeverBlank() {
        ValidationResult result = AdvancementsValidation.check(payload(-1, -1L, List.of(example("minecraft:a"))));
        assertFalse(result.valid());
        assertFalse(result.reason() == null || result.reason().isBlank());
    }
}
