package lol.candyriya.continuum.paper.modules.advancements;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One captured advancement, flattened for storage.
 *
 * <p>{@code key} is the advancement's namespaced key string (e.g.
 * {@code minecraft:story/mine_stone}), never a display name or a translation key —
 * those are not stable across server builds or locales, keys are.
 * {@code awardedCriteria} holds the criteria that were awarded at capture time,
 * sorted alphabetically and deduplicated. {@code done} is the advancement's done state at capture time
 * (all criteria awarded, vanilla grants the advancement itself). {@code awardedAt}
 * maps each awarded criterion to the wall-clock time it was awarded as epoch
 * milliseconds, taken from {@code AdvancementProgress#getDateAwarded}. It is kept for
 * forensics/diagnostics: Continuum cannot backdate criteria at restore (the public API
 * always stamps "now"), so these values describe the capture history, not the moment
 * the restore applied the criteria. Criteria the platform reported no date for are
 * simply absent from the map.</p>
 *
 * <p>All collections are defensively copied and sorted, so two captures of the same
 * state produce identical entries.</p>
 *
 * @param key             namespaced key of the advancement, e.g. {@code minecraft:story/mine_stone}; never blank
 * @param awardedCriteria the awarded criteria at capture time, sorted alphabetically and deduplicated; empty when only done state was known
 * @param done            whether the advancement was completed at capture time
 * @param awardedAt       award date per awarded criterion, epoch milliseconds; sorted by criterion; may be empty
 */
public record AdvancementEntry(@NotNull String key,
                               @NotNull List<String> awardedCriteria,
                               boolean done,
                               @NotNull Map<String, Long> awardedAt) {

    public AdvancementEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        key = key.trim();
        // sorted + deduplicated here, not just at capture: serialization must come out
        // identical for the same state no matter who built the entry
        awardedCriteria = awardedCriteria == null ? List.of() : List.copyOf(new TreeSet<>(awardedCriteria));
        if (awardedAt == null) {
            awardedAt = Map.of();
        } else {
            // tree-sorted for deterministic serialization; reject nulls up front so a
            // corrupt snapshot never makes it past the constructor half-checked
            var sorted = new TreeMap<String, Long>();
            for (Map.Entry<String, Long> e : awardedAt.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    throw new IllegalArgumentException("awarded_at must not contain null keys or values");
                }
                sorted.put(e.getKey(), e.getValue());
            }
            awardedAt = java.util.Collections.unmodifiableSortedMap(sorted);
        }
    }
}
