package lol.candyriya.continuum.paper.modules.statistics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Computes the restore-side reconciliation between a captured snapshot and the
 * local counter state of this server.
 *
 * <p>Statistics are cumulative, so the plan never lowers a counter. For every
 * captured cell the decision is exactly one of three outcomes:</p>
 * <ul>
 *   <li><b>catch up</b> — captured &gt; local: the snapshot knows more than
 *       this server does; the cell is scheduled for an absolute write of the
 *       captured value;</li>
 *   <li><b>keep local</b> — captured ≤ local: this server is ahead (it saw
 *       counters the snapshot does not know, e.g. it is the server that earned
 *       them and the snapshot predates that session); nothing is written;</li>
 *   <li><b>skip</b> — the key is unknown to this build or the local read
 *       refuses the cell; nothing is written, the rest of the grid proceeds.</li>
 * </ul>
 *
 * <p>Writes are absolute, not relative — applying the same plan twice leaves
 * the same state, which is what makes restore safe to retry after a crash.</p>
 *
 * <p>The planning logic is generic over the slot type so it can be tested
 * without platform enums; the Bukkit wiring lives in {@link StatisticsModule}.</p>
 */
public final class StatisticsRestorePlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsRestorePlanner.class);

    private StatisticsRestorePlanner() {
        // static planning only
    }

    /** Resolves a stored key onto an addressable cell; empty when unknown to this build. */
    @FunctionalInterface
    public interface SlotResolver<S> {
        Optional<S> resolve(String key);
    }

    /** One absolute counter write. */
    public record StatWrite<S>(S slot, long value) {
    }

    /**
     * The reconciliation outcome.
     *
     * @param writes         cells to raise locally, in payload order
     * @param keptAhead      cells where the local counter was equal to or ahead of the snapshot (no write)
     * @param skippedUnknown keys this build cannot resolve; they are never written
     * @param unreadable     keys whose local read refused; they are never written
     */
    public record RestorePlan<S>(List<StatWrite<S>> writes, int keptAhead, List<String> skippedUnknown, List<String> unreadable) {

        public RestorePlan {
            writes = List.copyOf(writes);
            skippedUnknown = List.copyOf(skippedUnknown);
            unreadable = List.copyOf(unreadable);
        }

        /** True when there is nothing to do. */
        public boolean isEmpty() {
            return writes.isEmpty();
        }
    }

    /**
     * Builds the plan for one player.
     *
     * @param captured     the counters captured in the snapshot
     * @param resolver     maps a stored key onto a cell; empty for unknown keys
     * @param localCurrent reads the cell as it stands on this server right now
     * @param <S>          the cell type (platform {@code StatSlot} on the live path)
     */
    public static <S> RestorePlan<S> plan(
            List<StatisticsPayload.Entry> captured,
            SlotResolver<S> resolver,
            StatisticsDump.CellReader<S> localCurrent) {
        List<StatWrite<S>> writes = new ArrayList<>();
        List<String> skippedUnknown = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        int keptAhead = 0;
        for (StatisticsPayload.Entry entry : captured) {
            Optional<S> resolved = resolver.resolve(entry.key());
            if (resolved.isEmpty()) {
                skippedUnknown.add(entry.key());
                continue;
            }
            S slot = resolved.get();
            final long local;
            try {
                local = localCurrent.read(slot);
            } catch (RuntimeException e) {
                LOGGER.warn("statistics read refused for {}; leaving the cell untouched", entry.key(), e);
                unreadable.add(entry.key());
                continue;
            }
            if (entry.value() > local) {
                writes.add(new StatWrite<>(slot, entry.value()));
            } else {
                keptAhead++;
            }
        }
        return new RestorePlan<>(writes, keptAhead, skippedUnknown, unreadable);
    }
}
