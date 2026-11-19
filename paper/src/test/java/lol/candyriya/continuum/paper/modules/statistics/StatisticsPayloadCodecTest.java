package lol.candyriya.continuum.paper.modules.statistics;

import lol.candyriya.continuum.paper.modules.vitals.JacksonCodecBridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests for the statistics payload, driven through the same mapper
 * the production snapshot pipeline builds (see {@link JacksonCodecBridge}).
 */
class StatisticsPayloadCodecTest {

    private final JacksonCodecBridge codec = JacksonCodecBridge.create();

    private static StatisticsPayload example() {
        return new StatisticsPayload(StatisticsPayload.FORMAT, List.of(
                new StatisticsPayload.Entry("block:mine_block:stone", 7L),
                new StatisticsPayload.Entry("item:use_item:diamond_sword", 3401L),
                new StatisticsPayload.Entry("untyped:deaths", 12L)));
    }

    @Test
    void roundTripPreservesEveryEntry() {
        StatisticsPayload original = example();
        StatisticsPayload decoded = codec.roundTrip(original, StatisticsPayload.class);

        assertEquals(original.format(), decoded.format());
        assertEquals(original.stats(), decoded.stats());
    }

    @Test
    void roundTripPreservesEntryOrder() {
        StatisticsPayload decoded = codec.roundTrip(example(), StatisticsPayload.class);

        assertEquals(List.of("block:mine_block:stone", "item:use_item:diamond_sword", "untyped:deaths"),
                decoded.stats().stream().map(StatisticsPayload.Entry::key).toList());
    }

    @Test
    void wireFormatUsesSnakeCaseKeysAndFormatMarker() {
        String json = codec.toJson(example());

        assertTrue(json.contains("\"format\":1"), json);
        assertTrue(json.contains("\"stats\":["), json);
        assertTrue(json.contains("\"key\":\"untyped:deaths\""), json);
        assertTrue(json.contains("\"value\":12"), json);
    }

    @Test
    void emptyStatsRoundTrip() {
        StatisticsPayload decoded = codec.roundTrip(
                new StatisticsPayload(StatisticsPayload.FORMAT, List.of()), StatisticsPayload.class);

        assertEquals(List.of(), decoded.stats());
    }
}
