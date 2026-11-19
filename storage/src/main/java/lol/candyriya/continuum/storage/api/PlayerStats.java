package lol.candyriya.continuum.storage.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The baseline (absolute) view of a player's statistics: exact values, never
 * deltas. Reads return what is stored right now; {@code writeStatsBaseline}
 * replaces the stored set wholesale inside one transaction.
 *
 * <p>Instances are immutable. {@link #values()} is copied and unmodifiable.</p>
 *
 * @param playerId the player these stats belong to, never null
 * @param values   stat key to exact value, never null
 */
public record PlayerStats(UUID playerId, Map<String, Long> values) {

    public PlayerStats {
        Objects.requireNonNull(playerId, "playerId must not be null");
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** The stored value for {@code statKey}, or 0 when the key was never recorded. */
    public long stat(String statKey) {
        Objects.requireNonNull(statKey, "statKey must not be null");
        return values.getOrDefault(statKey, 0L);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
