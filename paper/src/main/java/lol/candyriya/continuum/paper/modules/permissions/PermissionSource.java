package lol.candyriya.continuum.paper.modules.permissions;

/**
 * The one seam between Continuum and whoever actually answers permission questions on this
 * backend — the platform's super-perms implementation, LuckPerms, a custom provider, whatever
 * is installed. Implementations just answer; they do not cache, do not copy nodes into their
 * own storage and do not second-guess the engine.
 *
 * <p>Semantics follow Bukkit's super-perms: a node the player has explicitly is {@code true},
 * a node set to {@code false} is {@code false}, an unset node is whatever the registered
 * {@link org.bukkit.permissions.Permission} default says — and since Continuum never registers
 * its own {@code continuum.*} nodes on the Bukkit permission map, an unset node resolves to
 * {@code false} for everyone. Operators opt players in (or restrict them out) per group/player
 * in their permission engine of choice.</p>
 *
 * <p>Implementations must answer for any node, not just ones they know about; unknown nodes
 * answer {@code false} unless the platform says otherwise. Implementations should not throw for
 * a well-formed node they do not understand — they may throw for platform-level failures
 * (dead permission engine and the like); callers decide how to react.</p>
 *
 * <p>This interface exists so platform-agnostic code (and unit tests) can talk to permissions
 * without dragging Bukkit onto the classpath. See {@link BukkitPermissionSource} for the live
 * adapter.</p>
 */
@FunctionalInterface
public interface PermissionSource {

    /**
     * Whether the target of this source has {@code node}.
     *
     * @param node a well-formed dotted lowercase permission node, never null; validity is
     *             the caller's responsibility (see {@link PermissionGate}) — implementations
     *             may assume it was checked
     * @return true when the node is granted
     */
    boolean hasPermission(String node);
}
