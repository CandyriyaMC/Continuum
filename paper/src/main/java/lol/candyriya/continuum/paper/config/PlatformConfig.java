package lol.candyriya.continuum.paper.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Root of the backend-node configuration, mapped from {@code platform.yml}.
 *
 * <p>Everything node-local lives here (join gate, spawn policy). The shared,
 * network-wide settings live in {@code config.yml} and are mapped by the common
 * configuration loader — the two files never overlap in keys.</p>
 *
 * <p>Semantic validation beyond type checks lives in {@link PlatformConfigLoader}:
 * type mapping catches "max-failures is not a number", the loader catches
 * "max-failures is 0" with one report listing every problem found.</p>
 */
@ConfigSerializable
public record PlatformConfig(GateConfig gate, SpawnPolicyConfig spawnPolicy) {

    public PlatformConfig {
        if (gate == null) {
            gate = new GateConfig(true, 3, 10, 5, true);
        }
        if (spawnPolicy == null) {
            spawnPolicy = new SpawnPolicyConfig(SpawnMode.SERVER_SPAWN, SpawnPolicyConfig.DEFAULT_WORLD);
        }
    }
}
