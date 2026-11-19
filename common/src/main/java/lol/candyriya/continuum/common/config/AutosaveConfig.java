package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;

/**
 * Periodic snapshotting of online players.
 *
 * @param enabled  write snapshots on the interval while players are online
 * @param interval delay between snapshot sweeps; should comfortably exceed the time a full sweep takes
 */
@ConfigSerializable
public record AutosaveConfig(boolean enabled, Duration interval) {

    public AutosaveConfig {
        if (interval == null) {
            interval = Duration.ofSeconds(60);
        }
    }
}
