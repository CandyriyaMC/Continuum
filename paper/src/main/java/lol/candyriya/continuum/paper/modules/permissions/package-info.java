/**
 * The integration surface for permission-aware sync decisions.
 *
 * <p><b>What lives here.</b></p>
 * <ul>
 *   <li>{@link lol.candyriya.continuum.paper.modules.permissions.ContinuumPermissionContext} —
 *       the platform service that reports the permission-relevant <em>context</em> of a player
 *       (world, plus this backend's network server id when configured) and encodes it into a
 *       stable, diagnostics-only string.</li>
 *   <li>{@link lol.candyriya.continuum.paper.modules.permissions.PermissionGate} /
 *       {@link lol.candyriya.continuum.paper.modules.permissions.BukkitPermissionGate} —
 *       the single funnel for a permission decision before syncing sensitive state (for instance a
 *       custom sync adapter honouring an operator-set restriction like {@code continuum.restrict.inventory}).
 *       Super-perms answers; the installed permission engine (LuckPerms or not) decides. The Player-typed
 *       entry points live in the Bukkit-specific class so the core gate needs no platform types.</li>
 *   <li>{@link lol.candyriya.continuum.paper.modules.permissions.PermissionSource} /
 *       {@link lol.candyriya.continuum.paper.modules.permissions.BukkitPermissionSource} —
 *       the seam between the gate and the platform, so decisions can be unit-tested without
 *       a server.</li>
 * </ul>
 *
 * <p><b>What deliberately does not live here.</b> There is no permission registry, no node
 * copy, no group model and no permission cache in this package — permissions belong to the
 * permission engine, which already synchronises them across backends (or does not, and no
 * copy from Continuum would fix that). No first-party module of this plugin registers or
 * requires a permission node.</p>
 *
 * <p>The first-party id {@code continuum.permissions} stays reserved in the module-id
 * namespace for a future integration-scoped payload; this package intentionally ships no
 * synchronization module for it — there is no permission state to snapshot or restore.</p>
 */
package lol.candyriya.continuum.paper.modules.permissions;
