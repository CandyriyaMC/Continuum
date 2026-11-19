package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;
import java.util.List;

/**
 * Automatic recovery for sessions that crashed mid-flight.
 *
 * @param enabled      re-scan for orphaned sessions on the interval
 * @param interval     delay between recovery sweeps
 * @param restoreOrder module restore order for partial recovery; empty means
 *                     {@link KnownModules#DEFAULT_RESTORE_ORDER}. Ids must be known.
 */
@ConfigSerializable
public record RecoveryConfig(boolean enabled, Duration interval, List<String> restoreOrder) {

    public RecoveryConfig {
        if (interval == null) {
            interval = Duration.ofSeconds(30);
        }
        if (restoreOrder == null) {
            restoreOrder = List.of();
        } else {
            for (String moduleId : restoreOrder) {
                if (moduleId == null || moduleId.isBlank()) {
                    throw new IllegalArgumentException("sync.recovery.restore-order: entries must be non-blank module ids");
                }
            }
            restoreOrder = List.copyOf(restoreOrder);
        }
    }
}
