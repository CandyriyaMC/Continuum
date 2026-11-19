package lol.candyriya.continuum.paper.modules.location;

import org.bukkit.Location;
import org.bukkit.World;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One captured world position. Used for all three sections of the payload — the
 * player's position, the respawn point and the last death point.
 *
 * <p>{@link #world} is the world <em>name</em> ({@code World#getName()}), never a
 * key or a dimension slash-separated path: world names are the cross-node contract
 * in a multi-backend deployment, keys are not (every overworld reports the same
 * key). {@link #environment} is {@code World#getEnvironment()}'s name (NORMAL /
 * NETHER / THE_END / CUSTOM) and exists so the restore path can refuse to drop
 * coordinates into a same-named world of a different dimension family — a classic
 * silent-corruption case when one backend re-creates a world with a different
 * environment. The world name itself is not normalized beyond trim; case-insensitive
 * comparison happens only where an action depends on it (exclusions).</p>
 *
 * <p>Coordinates are plain finite doubles; magnitude sanity is checked at restore
 * time (see {@link LocationValidation}), not here, so a snapshot written by a
 * future build still decodes.</p>
 *
 * @param world       the world name, never null or blank
 * @param environment the captured dimension family, or null when unknown
 * @param x           absolute x
 * @param y           absolute y
 * @param z           absolute z
 * @param yaw         yaw at capture, degrees
 * @param pitch       pitch at capture, degrees
 */
public record PositionSnapshot(
        @NotNull String world,
        @Nullable String environment,
        double x,
        double y,
        double z,
        float yaw,
        float pitch) {

    public PositionSnapshot {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be null or blank");
        }
        world = world.trim();
        if (environment != null) {
            String trimmed = environment.trim();
            environment = trimmed.isEmpty() ? null : trimmed;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite, got " + x + ", " + y + ", " + z);
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("yaw and pitch must be finite, got " + yaw + ", " + pitch);
        }
    }

    /**
     * Snapshots {@code location}. Returns {@code null} when the location or its world is
     * missing — callers decide whether the section is optional (respawn point, last death
     * point: yes) or fatal for the capture (position: yes).
     */
    public static @Nullable PositionSnapshot of(@Nullable Location location) {
        if (location == null) {
            return null;
        }
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return new PositionSnapshot(world.getName(), environmentOf(world),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /** Reads the environment defensively; a world with an unreadable environment captures as null, never as a crash. */
    public static @Nullable String environmentOf(@NotNull World world) {
        try {
            var environment = world.getEnvironment();
            return environment == null ? null : environment.name();
        } catch (RuntimeException e) {
            // not worth failing a capture over — the dimension family is a restore-time guard, not the payload's identity
            return null;
        }
    }
}
