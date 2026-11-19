package lol.candyriya.continuum.paper.modules.statistics;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsValidationTest {

    private static StatisticsPayload payload(int format, List<StatisticsPayload.Entry> entries) {
        return new StatisticsPayload(format, entries);
    }

    private static StatisticsPayload.Entry entry(String key, long value) {
        return new StatisticsPayload.Entry(key, value);
    }

    @Test
    void currentFormatIsAccepted() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("untyped:deaths", 12L))));
        assertTrue(result.valid());
    }

    @Test
    void emptyStatsListIsAccepted() {
        // a player who never played captures an empty payload; that is fine
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT, List.of()));
        assertTrue(result.valid());
    }

    @Test
    void olderAndNewerFormatsAreRejected() {
        assertFalse(StatisticsValidation.validate(payload(0, List.of())).valid());
        assertFalse(StatisticsValidation.validate(payload(StatisticsPayload.FORMAT + 1, List.of())).valid());
    }

    @Test
    void nullPayloadIsRejected() {
        assertFalse(StatisticsValidation.validate(null).valid());
    }

    @Test
    void negativeValueIsRejected() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("untyped:deaths", -1L))));
        assertFalse(result.valid());
    }

    @Test
    void valueBeyondIntRangeIsRejected() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("untyped:play_one_minute", Integer.MAX_VALUE + 1L))));
        assertFalse(result.valid());
    }

    @Test
    void valueAtIntRangeEdgeIsAccepted() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("untyped:play_one_minute", Integer.MAX_VALUE))));
        assertTrue(result.valid());
    }

    @Test
    void malformedKeyIsRejected() {
        for (String key : List.of("", "DEATHS", "untyped", "untyped:", "untyped:a:b", "item:use_item:Stone", "block:mine_block:minecraft:stone")) {
            ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                    List.of(entry(key, 1L))));
            assertFalse(result.valid(), "expected rejection: " + key);
        }
    }

    @Test
    void unknownButWellFormedKeysAreAccepted() {
        // captured on a newer server — restore skips them, validation must not
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("item:some_future_stat:some_future_item_2030", 42L),
                        entry("entity:kill_entity:some_future_mob_2030", 1L),
                        entry("untyped:some_future_stat", 7L))));
        assertTrue(result.valid());
    }

    @Test
    void duplicateKeysAreRejected() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("untyped:deaths", 1L), entry("untyped:deaths", 2L))));
        assertFalse(result.valid());
    }

    @Test
    void sameKeyInDifferentFamiliesIsNotADuplicate() {
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT,
                List.of(entry("item:use_item:stone", 1L), entry("block:mine_block:stone", 2L))));
        assertTrue(result.valid());
    }

    @Test
    void beyondMaxEntriesIsRejected() {
        List<StatisticsPayload.Entry> entries = new ArrayList<>(StatisticsPayload.MAX_ENTRIES + 1);
        for (int i = 0; i < StatisticsPayload.MAX_ENTRIES + 1; i++) {
            // pad with numbered but syntactically valid and unique keys
            entries.add(entry("untyped:stat_" + i, 1L));
        }
        ValidationResult result = StatisticsValidation.validate(payload(StatisticsPayload.FORMAT, entries));
        assertFalse(result.valid());
    }

    @Test
    void invalidResultNeverCarriesABlankReason() {
        ValidationResult result = StatisticsValidation.validate(payload(-1, null));
        assertFalse(result.valid());
        assertTrue(result.reason() != null && !result.reason().isBlank());
    }
}
