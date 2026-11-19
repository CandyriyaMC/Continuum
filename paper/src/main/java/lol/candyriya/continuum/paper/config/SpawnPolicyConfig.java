package lol.candyriya.continuum.paper.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Spawn-location policy tuning for this backend node.
 *
 * @param mode  which spawn decision applies to players whose snapshot holds no usable location yet
 * @param world world for SERVER_SPAWN (and as the LAST_KNOWN_LOCATION fallback); empty = server default world
 */
@ConfigSerializable
public record SpawnPolicyConfig(SpawnMode mode, String world) {

    public static final String DEFAULT_WORLD = "";

    // world names: letters, digits, '.', '_', '-' — same conservative charset as elsewhere in the config
    private static final java.util.regex.Pattern WORLD_NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    void validate(java.util.List<String> problems) {
        if (mode == null) {
            problems.add("spawn-policy.mode: must be one of SERVER_SPAWN, LAST_KNOWN_LOCATION, DISABLED");
        }
        if (world == null) {
            problems.add("spawn-policy.world: must be a world name or empty (= the server default world)");
            return;
        }
        String trimmed = world.trim();
        if (!trimmed.isEmpty() && !WORLD_NAME.matcher(trimmed).matches()) {
            problems.add("spawn-policy.world: must match [A-Za-z0-9._-]{1,64} or be empty (= the server default world)");
        }
    }
}
