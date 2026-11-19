package lol.candyriya.continuum.paper.modules.location;

import org.jetbrains.annotations.Nullable;

/**
 * Server-level facts the module needs, isolated for the same reason as {@link WorldLookup}:
 * the restore planner and its tests must not touch a live server.
 */
public interface ServerLookup {

    /**
     * The name of the default (first loaded) world, or {@code null} when no world is loaded
     * or the fact cannot be read. Used only for diagnostics — the module never teleports
     * to the server spawn itself; placement belongs to the gate-time spawn policy.
     */
    @Nullable String defaultWorldName();
}
