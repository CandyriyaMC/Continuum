package lol.candyriya.continuum.paper.modules.statistics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsDumpTest {

    /** A fake cell standing in for {@code StatSlot} — the collection logic only needs a key. */
    private record Cell(String key, long value) {
    }

    private static StatisticsPayload take(Map<String, Long> counters) {
        List<Cell> cells = new ArrayList<>();
        counters.forEach((key, value) -> cells.add(new Cell(key, value)));
        return StatisticsDump.take(cells, Cell::key, cell -> cell.value());
    }

    @Test
    void zeroValuedCellsAreOmitted() {
        StatisticsPayload payload = take(Map.of(
                "untyped:deaths", 0L,
                "untyped:walk_one_cm", 1500L,
                "item:use_item:diamond_sword", 0L));

        assertEquals(1, payload.stats().size());
        assertEquals("untyped:walk_one_cm", payload.stats().get(0).key());
        assertEquals(1500L, payload.stats().get(0).value());
    }

    @Test
    void allZerosProducesAnEmptyPayload() {
        StatisticsPayload payload = take(Map.of(
                "untyped:deaths", 0L,
                "block:mine_block:stone", 0L));

        assertTrue(payload.stats().isEmpty());
    }

    @Test
    void negativeReadsAreOmittedNotClamped() {
        StatisticsPayload payload = take(Map.of(
                "untyped:deaths", -5L,
                "untyped:walk_one_cm", 1L));

        assertEquals(1, payload.stats().size());
        assertEquals("untyped:walk_one_cm", payload.stats().get(0).key());
    }

    @Test
    void aThrowingCellDoesNotAbortTheDump() {
        List<Cell> cells = List.of(
                new Cell("untyped:deaths", 3L),
                new Cell("boom", Long.MIN_VALUE),
                new Cell("untyped:walk_one_cm", 42L));

        StatisticsPayload payload = StatisticsDump.take(cells, Cell::key, cell -> {
            if ("boom".equals(cell.key())) {
                throw new IllegalStateException("platform refused the cell");
            }
            return cell.value();
        });

        assertEquals(List.of(
                new StatisticsPayload.Entry("untyped:deaths", 3L),
                new StatisticsPayload.Entry("untyped:walk_one_cm", 42L)), payload.stats());
    }

    @Test
    void outputIsKeySortedRegardlessOfInputOrder() {
        List<Cell> cells = new ArrayList<>();
        Map<String, Long> unsorted = new TreeMap<>(Collections.reverseOrder());
        unsorted.put("untyped:walk_one_cm", 1L);
        unsorted.put("block:mine_block:stone", 2L);
        unsorted.put("item:use_item:diamond_sword", 3L);
        unsorted.put("untyped:deaths", 4L);
        unsorted.forEach((key, value) -> cells.add(new Cell(key, value)));

        StatisticsPayload payload = StatisticsDump.take(cells, Cell::key, Cell::value);

        List<String> keys = payload.stats().stream().map(StatisticsPayload.Entry::key).toList();
        assertEquals(List.of(
                "block:mine_block:stone",
                "item:use_item:diamond_sword",
                "untyped:deaths",
                "untyped:walk_one_cm"), keys);
    }

    @Test
    void identicalStatesProduceIdenticalPayloads() {
        Map<String, Long> counters = Map.of(
                "item:use_item:diamond_sword", 3401L,
                "untyped:deaths", 12L,
                "block:mine_block:stone", 7L);

        StatisticsPayload first = take(counters);
        StatisticsPayload second = take(new TreeMap<>(counters));

        assertEquals(first.stats(), second.stats());
    }

    @Test
    void fromCountersDropsNonPositiveValuesAndSorts() {
        Map<String, Long> counters = new TreeMap<>();
        counters.put("untyped:deaths", 12L);
        counters.put("untyped:walk_one_cm", 0L);
        counters.put("item:use_item:stick", 1L);

        StatisticsPayload payload = StatisticsDump.fromCounters(counters);

        assertEquals(List.of(
                new StatisticsPayload.Entry("item:use_item:stick", 1L),
                new StatisticsPayload.Entry("untyped:deaths", 12L)), payload.stats());
        assertEquals(StatisticsPayload.FORMAT, payload.format());
    }
}
