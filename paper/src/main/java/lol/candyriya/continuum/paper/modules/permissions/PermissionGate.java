package lol.candyriya.continuum.paper.modules.permissions;

import lol.candyriya.continuum.api.module.ModuleIds;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one funnel every permission-sensitive sync decision in Continuum goes through —
 * platform-neutral half. {@link BukkitPermissionGate} holds the {@code Player}-typed entry
 * points and delegates here; everything platform-specific is split out so this class needs
 * no Bukkit types and decisions can be unit-tested without a server.
 *
 * <p><b>No competing permission system.</b> Continuum never registers, copies, caches or
 * re-evaluates permission nodes — the engine installed on the backend (LuckPerms or not)
 * owns the truth, and super-perms answer for us. If the engine already synchronises
 * permissions across backends, Continuum stays out of the way; if it does not, that is the
 * operator's concern, not something a second copy could fix.</p>
 *
 * <p><b>Continuum consumes no nodes.</b> No first-party module or command of this plugin
 * requires a permission to do its job, and this class registers none. It exists for the code
 * paths that do need an explicit decision before touching sensitive state — custom sync
 * adapters honouring an operator restriction, for instance. Until a caller invokes
 * {@link #hasSyncPermission}, nothing is evaluated and nothing is stored.</p>
 *
 * <p><b>Node convention.</b> Nodes are dotted lowercase segments ({@code a.b.c}); each segment
 * is 1–64 characters of {@code a-z}, {@code 0-9}, {@code _} or {@code -}, starting and ending
 * with an alphanumeric — the same grammar as module ids. Whole node at most 256 characters.
 * The lowercase rule matches what permission engines actually grant — a node with uppercase
 * characters would silently never match a lowercase grant, so it is rejected loudly instead.
 * Restrict nodes are derived, not hand-spelled: for a module id {@code ns.some.module} the
 * restriction node is {@code ns.restrict.some.module} (see {@link #restrictNode(String)}).</p>
 *
 * <p><b>Restriction semantics.</b> {@code *.restrict.<module>} nodes are opt-in: grant one
 * to a player or group and Continuum-aware code skips restoring that module (or applies an
 * equivalent restriction) for them. Absent node, full restore — restrictions never deny by
 * default. Note this is a policy lever for operators, <em>not a security boundary</em> and
 * not access control for gameplay logic; the plugin's own commands are gated separately.</p>
 *
 * <p>Decisions are never memoised: every call re-queries the source, because permission
 * engines change grants live.</p>
 */
public final class PermissionGate {

    /** The segment inserted after the namespace to build a restriction node. */
    public static final String RESTRICT_SEGMENT = "restrict";

    /** The most a node may be — matches neither module ids (128) nor anything sacred, just sanity. */
    public static final int MAX_NODE_LENGTH = 256;

    /** Each segment is 1–64 chars, starts and ends alphanumeric — same convention as module ids. */
    private static final Pattern NODE =
            Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?(\\.[a-z0-9](?:[a-z0-9_-]{0,62}[a-z0-9])?)*");

    private PermissionGate() {
        // static funnel only
    }

    /**
     * Asks an arbitrary source whether {@code node} is granted. The seam platform-agnostic
     * code (and tests) use.
     *
     * @param source the permission source to ask, never null
     * @param node a well-formed dotted lowercase node, never null
     * @return true when the node is granted
     * @throws IllegalArgumentException when the node is null or malformed
     * @throws NullPointerException when source is null
     */
    public static boolean hasSyncPermission(@NotNull PermissionSource source, @NotNull String node) {
        Objects.requireNonNull(source, "source");
        requireValidNode(node);
        return source.hasPermission(node);
    }

    /**
     * The restriction node for a module: {@code ns.some.module} → {@code ns.restrict.some.module}.
     * Third-party namespaces are preserved — a module {@code myplugin.cool_module} restricts via
     * {@code myplugin.restrict.cool_module}.
     *
     * @param moduleId a well-formed module id, never null
     * @return the restriction node
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when moduleId is not a well-formed module id
     * @throws NullPointerException when moduleId is null
     */
    public static @NotNull String restrictNode(@NotNull String moduleId) {
        ModuleIds.requireValid(moduleId);
        int dot = moduleId.indexOf('.');
        return moduleId.substring(0, dot) + "." + RESTRICT_SEGMENT + "." + moduleId.substring(dot + 1);
    }

    /**
     * Whether restoring {@code moduleId} is allowed for the target of {@code source} —
     * true unless the restriction node is granted. See the class javadoc for the semantics.
     *
     * @param source the permission source to ask, never null
     * @param moduleId a well-formed module id, never null
     * @return true when no restriction is in place
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when moduleId is not a well-formed module id
     * @throws NullPointerException when source or moduleId is null
     */
    public static boolean allowsRestore(@NotNull PermissionSource source, @NotNull String moduleId) {
        return !hasSyncPermission(source, restrictNode(moduleId));
    }

    private static void requireValidNode(String node) {
        if (node == null) {
            throw new IllegalArgumentException("permission node must not be null");
        }
        if (node.isBlank()) {
            throw new IllegalArgumentException("permission node must not be blank");
        }
        if (node.length() > MAX_NODE_LENGTH) {
            throw new IllegalArgumentException("permission node must not exceed " + MAX_NODE_LENGTH + " characters");
        }
        if (!NODE.matcher(node).matches()) {
            throw new IllegalArgumentException(
                    "permission node must be dotted lowercase segments of 1-64 a-z, 0-9, '_', '-' chars each, "
                            + "starting and ending alphanumeric: " + node);
        }
    }
}
