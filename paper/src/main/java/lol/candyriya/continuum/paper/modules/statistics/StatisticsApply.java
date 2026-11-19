package lol.candyriya.continuum.paper.modules.statistics;

import org.bukkit.OfflinePlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a {@link StatisticsRestorePlanner.RestorePlan} against a live
 * player. Every write is an absolute set of a value the platform already
 * agreed to store, so applying the plan is idempotent.
 *
 * <p>A write the platform refuses does not abort the remaining cells; it is
 * logged, collected, and reported back so the module can fail honestly
 * instead of silently half-restoring. Callers must not retry individual
 * failed writes blindly — the platform contract is authoritative.</p>
 */
public final class StatisticsApply {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatisticsApply.class);

    private StatisticsApply() {
        // static helpers only
    }

    /**
     * @param applied number of cells written
     * @param failed  keys whose write was refused by the platform
     */
    public record ApplyResult(int applied, List<String> failed) {

        public ApplyResult {
            failed = List.copyOf(failed);
        }
    }

    public static ApplyResult apply(OfflinePlayer player, List<StatisticsRestorePlanner.StatWrite<StatSlot>> writes) {
        int applied = 0;
        List<String> failed = new ArrayList<>();
        for (StatisticsRestorePlanner.StatWrite<StatSlot> write : writes) {
            StatSlot slot = write.slot();
            // the platform counters are int-wide; a snapshot whose values escaped
            // validation must not wrap through a cast — refuse the cell instead
            if (write.value() > Integer.MAX_VALUE) {
                LOGGER.warn("statistics write refused for {} (value {} exceeds int range)", slot.key(), write.value());
                failed.add(slot.key());
                continue;
            }
            int value = (int) write.value();
            try {
                switch (slot.statistic().getType()) {
                    case UNTYPED -> player.setStatistic(slot.statistic(), value);
                    case ITEM, BLOCK -> player.setStatistic(slot.statistic(), slot.material(), value);
                    case ENTITY -> player.setStatistic(slot.statistic(), slot.entityType(), value);
                }
                applied++;
            } catch (RuntimeException e) {
                LOGGER.warn("statistics write refused for {} (value {})", slot.key(), value, e);
                failed.add(slot.key());
            }
        }
        return new ApplyResult(applied, failed);
    }
}
