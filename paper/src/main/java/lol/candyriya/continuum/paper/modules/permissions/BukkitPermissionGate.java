package lol.candyriya.continuum.paper.modules.permissions;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The {@code Player}-typed entry points of the permission gate. Kept in a separate class so
 * {@link PermissionGate} stays loadable (and unit-testable) without Bukkit on the classpath —
 * same split as {@link BukkitPermissionSource} vs {@link PermissionSource}.
 *
 * <p>Everything here is a one-line delegate: validate, wrap, ask. Behaviour, node grammar and
 * restriction semantics are documented on {@link PermissionGate} and defined there.</p>
 */
public final class BukkitPermissionGate {

    private BukkitPermissionGate() {
        // static funnel only
    }

    /**
     * Asks the player whether {@code node} is granted — super-perms on the backend, which
     * routes to whatever permission engine is installed.
     *
     * @param player the live player, never null
     * @param node a well-formed dotted lowercase node, never null
     * @return true when the node is granted
     * @throws IllegalArgumentException when the node is null or malformed
     * @throws NullPointerException when player is null
     */
    public static boolean hasSyncPermission(@NotNull Player player, @NotNull String node) {
        return PermissionGate.hasSyncPermission(new BukkitPermissionSource(Objects.requireNonNull(player, "player")), node);
    }

    /**
     * Whether restoring {@code moduleId} is allowed for the player —
     * true unless the restriction node is granted.
     *
     * @param player the live player, never null
     * @param moduleId a well-formed module id, never null
     * @return true when no restriction is in place
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when moduleId is not a well-formed module id
     * @throws NullPointerException when player or moduleId is null
     */
    public static boolean allowsRestore(@NotNull Player player, @NotNull String moduleId) {
        return PermissionGate.allowsRestore(new BukkitPermissionSource(Objects.requireNonNull(player, "player")), moduleId);
    }
}
