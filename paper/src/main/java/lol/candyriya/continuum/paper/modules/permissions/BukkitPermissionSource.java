package lol.candyriya.continuum.paper.modules.permissions;

import org.bukkit.permissions.Permissible;

import java.util.Objects;

/**
 * A {@link PermissionSource} backed by a live Bukkit {@link Permissible} — in practice a
 * {@code Player}, which reaches super-perms through the permissions provider installed on
 * the server (LuckPerms or not). No nodes are registered, copied or cached here; the answer
 * is whatever the platform evaluates at call time.
 *
 * <p>The adapter is intentionally dumb: node grammar validation lives in {@link PermissionGate},
 * which is also the sanctioned funnel for decision-making — call that, not this. Direct use of
 * this adapter still fails fast on a null node.</p>
 */
public final class BukkitPermissionSource implements PermissionSource {

    private final Permissible target;

    public BukkitPermissionSource(Permissible target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public boolean hasPermission(String node) {
        return target.hasPermission(Objects.requireNonNull(node, "node"));
    }
}
