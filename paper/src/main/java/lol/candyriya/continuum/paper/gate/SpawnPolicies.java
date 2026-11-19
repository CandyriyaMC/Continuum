package lol.candyriya.continuum.paper.gate;

import lol.candyriya.continuum.paper.config.SpawnMode;
import lol.candyriya.continuum.paper.config.SpawnPolicyConfig;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import org.jetbrains.annotations.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

/**
 * Builds the {@link SpawnPolicy} for the configured {@link SpawnMode}.
 *
 * <p>Policy chain for {@code LAST_KNOWN_LOCATION}: last-known location from the
 * provider, falling back to the server spawn when unavailable or when the
 * provider fails — a missing location must never block a login, it just sends
 * the player to the world spawn like {@code SERVER_SPAWN} would.</p>
 */
public final class SpawnPolicies {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpawnPolicies.class);

    private SpawnPolicies() {
    }

    /** A provider that always comes up empty — the default until the location integration lands. */
    public static LastKnownLocationProvider emptyProvider() {
        return _ -> Optional.empty();
    }

    /** Creates the policy for the given configuration. Never null. */
    public static SpawnPolicy fromConfig(SpawnPolicyConfig config, LastKnownLocationProvider provider) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(provider, "provider");
        return switch (config.mode() == null ? SpawnMode.SERVER_SPAWN : config.mode()) {
            case SERVER_SPAWN -> new ServerSpawnPolicy(config.world());
            case LAST_KNOWN_LOCATION -> new LastKnownLocationPolicy(config.world(), provider);
            case DISABLED -> new DisabledSpawnPolicy();
        };
    }

    /** Spawn at the configured world's spawn point; empty config world = the server's default (first) world. */
    private static final class ServerSpawnPolicy implements SpawnPolicy {

        private final @Nullable String worldName;

        private ServerSpawnPolicy(String worldName) {
            this.worldName = (worldName == null || worldName.isBlank()) ? null : worldName.trim();
        }

        @Override
        public @Nullable Location decide(SpawnContext context) {
            return serverSpawn(worldName);
        }
    }

    /** Last-known location when the provider has one; otherwise the server spawn. */
    private static final class LastKnownLocationPolicy implements SpawnPolicy {

        private final @Nullable String worldName;
        private final LastKnownLocationProvider provider;

        private LastKnownLocationPolicy(String worldName, LastKnownLocationProvider provider) {
            this.worldName = (worldName == null || worldName.isBlank()) ? null : worldName.trim();
            this.provider = provider;
        }

        @Override
        public @Nullable Location decide(SpawnContext context) {
            try {
                Optional<Location> lastKnown = provider.lastKnownLocation(context.playerId());
                if (lastKnown.isPresent() && lastKnown.get() != null && lastKnown.get().getWorld() != null) {
                    return lastKnown.get();
                }
            } catch (Exception e) {
                // a failing provider must never block the login — fall back and say so
                LOGGER.warn("last-known location provider failed for player {}; falling back to the server spawn", context.playerId(), e);
            }
            return serverSpawn(worldName);
        }
    }

    /** No override: whatever the platform decides stands. */
    private static final class DisabledSpawnPolicy implements SpawnPolicy {

        @Override
        public @Nullable Location decide(SpawnContext context) {
            return null;
        }
    }

    private static @Nullable Location serverSpawn(@Nullable String worldName) {
        World world = resolveWorld(worldName);
        if (world == null) {
            return null;
        }
        // fresh copy every call — the world's spawn Location object is shared mutable state
        Location spawn = world.getSpawnLocation();
        return new Location(world, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
    }

    private static @Nullable World resolveWorld(@Nullable String worldName) {
        if (worldName != null) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                return world;
            }
            LOGGER.warn("spawn-policy.world '{}' is not loaded; using the server's default world instead", worldName);
        }
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            LOGGER.warn("no worlds are loaded; the platform default spawn stands");
            return null;
        }
        return worlds.getFirst();
    }
}
