package lol.candyriya.continuum.paper.modules.statistics;

import org.bukkit.OfflinePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Reads counter cells and packs the non-zero ones into a payload.
 *
 * <p>The collection logic ({@link #take(Collection, Function, CellReader)}) is
 * generic and platform-free: callers supply the cells, a key extractor and a
 * reader, which keeps it testable without a server. The Bukkit wiring is
 * {@link #player(OfflinePlayer)} + {@link #dump(CellReader)}, used on the
 * player's own thread during capture. A read that the platform rejects is
 * logged and skipped, never fatal — one refusing stat does not void the
 * whole dump.</p>
 *
 * <p>Entries with value {@code 0} are omitted (they carry no information
 * under the restore-side catch-up rule) and the output is key-sorted, so
 * identical states produce byte-identical payloads.</p>
 */
public final class StatisticsDump {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsDump.class);

    private StatisticsDump() {
        // static helpers only
    }

    /** Reads one cell; negative results mean "not legitimate", anything above zero is a counter. */
    @FunctionalInterface
    public interface CellReader<S> {
        long read(S slot);
    }

    /** Platform adapter over the stat getters (in-memory for online players). */
    public static CellReader<StatSlot> player(OfflinePlayer player) {
        return slot -> switch (slot.statistic().getType()) {
            case UNTYPED -> player.getStatistic(slot.statistic());
            case ITEM, BLOCK -> player.getStatistic(slot.statistic(), slot.material());
            case ENTITY -> player.getStatistic(slot.statistic(), slot.entityType());
        };
    }

    /** Captures the full platform grid; zero cells are omitted, output is key-sorted. */
    public static StatisticsPayload dump(CellReader<StatSlot> reader) {
        return take(StatSlots.all(), StatSlot::key, reader);
    }

    /**
     * Collects non-zero cells into a payload.
     *
     * @param cells  the candidate cells, in any order
     * @param keyOf  extracts the qualified key of a cell
     * @param reader reads the counter; a throwing cell is logged and omitted
     */
    public static <S> StatisticsPayload take(Collection<S> cells, Function<S, String> keyOf, CellReader<S> reader) {
        Map<String, Long> counters = new TreeMap<>();
        for (S cell : cells) {
            String key = keyOf.apply(cell);
            final long value;
            try {
                value = reader.read(cell);
            } catch (RuntimeException e) {
                LOGGER.warn("statistics read failed for {}; omitting the cell", key, e);
                continue;
            }
            if (value < 0) {
                LOGGER.warn("statistics cell {} read as negative ({}); omitting it", key, value);
                continue;
            }
            counters.put(key, value);
        }
        return fromCounters(counters);
    }

    /**
     * Builds a payload from a key → counter map: zero cells are dropped, the
     * rest is sorted by key. Duplicate keys are impossible in a map — hostile
     * payloads with duplicates are rejected at validation time instead.
     */
    public static StatisticsPayload fromCounters(Map<String, Long> counters) {
        List<StatisticsPayload.Entry> entries = new ArrayList<>(counters.size());
        for (Map.Entry<String, Long> cell : counters.entrySet()) {
            long value = cell.getValue();
            if (value <= 0) {
                continue;
            }
            entries.add(new StatisticsPayload.Entry(cell.getKey(), value));
        }
        entries.sort(Comparator.comparing(StatisticsPayload.Entry::key));
        return new StatisticsPayload(StatisticsPayload.FORMAT, entries);
    }
}
