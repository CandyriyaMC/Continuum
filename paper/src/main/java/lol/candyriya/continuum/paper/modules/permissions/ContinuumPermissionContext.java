package lol.candyriya.continuum.paper.modules.permissions;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The platform service that hands out the permission-relevant context of a player —
 * "under which circumstances does this decision happen?" for snapshot diagnostics and for
 * modules that gate their behaviour on context (an RPG gear unlock, say).
 *
 * <p><b>What it reports.</b> Exactly two tags, both cheap and stable:</p>
 * <ul>
 *   <li>{@value #KEY_WORLD} — the name of the world the player is in. Always present.</li>
 *   <li>{@value #KEY_SERVER} — this backend's network server id ({@code network.server-id},
 *       e.g. {@code survival-1}). Present only when the service was constructed with one;
 *       on a proxy-integrated deployment that mirrors the proxy's server name, this tag is
 *       the honest answer to "which server did the snapshot come from?".</li>
 * </ul>
 *
 * <p><b>What it deliberately does NOT report.</b> Nothing about permission nodes, groups,
 * roles or engines. Exposing those would leak sensitive access-control information into
 * every snapshot and log line, and every consumer would grow a dependency on one engine's
 * model. Context here is environment, not privilege.</p>
 *
 * <p><b>LuckPerms, Vault, anything else.</b> Not consulted, not linked, not required. World
 * names come from the platform; the server id comes from this node's own config. If a
 * deployment wants LuckPerms' own contexts (per-world profiles, meta playtime, ...), that is
 * an integration decision for that deployment's wiring — this module stays super-perms safe
 * and works identically with no permission engine installed.</p>
 *
 * <p><b>Threading.</b> Call {@link #currentContext(Player)} on the player's owning thread —
 * the main thread on Paper, the player's region thread on Folia — like every other platform
 * read. The service keeps no mutable state.</p>
 *
 * <p><b>Diagnostics encoding.</b> {@link #encodedContext(Player)} returns the stable form for
 * embedding into snapshot diagnostics when/if the snapshot schema gains a metadata slot; it is
 * never parsed back into decisions. See {@link PermissionContext}.</p>
 */
public final class ContinuumPermissionContext {

    /** The tag key for the player's world. */
    public static final String KEY_WORLD = "world";

    /** The tag key for this backend's network server id. */
    public static final String KEY_SERVER = "server";

    /** Same dns-ish shape the config loader enforces for {@code network.server-id}. */
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?");

    /** Null when no server id is configured — world-only contexts. */
    private final String serverId;

    /** World-only contexts. */
    public ContinuumPermissionContext() {
        this.serverId = null;
    }

    /**
     * Contexts that include the backend's network server id.
     *
     * @param serverId this server's network-unique id, e.g. {@code survival-1}; must match the
     *                 config convention: lowercase dns-ish, at most 64 characters, never blank.
     *                 Pass null for the world-only behaviour of {@link #ContinuumPermissionContext()}.
     * @throws IllegalArgumentException when serverId is blank or malformed
     */
    public ContinuumPermissionContext(@Nullable String serverId) {
        if (serverId != null) {
            requireValidServerId(serverId);
        }
        this.serverId = serverId;
    }

    /**
     * The context of the player right now.
     *
     * @param player the live player, never null
     * @return the context, never null — world tag always present, server tag when configured
     * @throws NullPointerException when player is null
     */
    public @NotNull PermissionContext currentContext(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        return assemble(player.getWorld().getName(), serverId);
    }

    /**
     * {@link #currentContext(Player)} in the stable encoded form — for logs and snapshot
     * diagnostics only, never for decisions.
     *
     * @param player the live player, never null
     * @return the encoded context, never null
     * @throws NullPointerException when player is null
     */
    public @NotNull String encodedContext(@NotNull Player player) {
        return currentContext(player).encode();
    }

    /** Pure assembly, split out so tests do not need a Player. Validates everything. */
    static @NotNull PermissionContext assemble(@NotNull String worldName, @Nullable String serverId) {
        Objects.requireNonNull(worldName, "worldName");
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        if (serverId != null) {
            requireValidServerId(serverId);
        }
        var entries = new TreeMap<String, String>();
        entries.put(KEY_WORLD, worldName);
        if (serverId != null) {
            entries.put(KEY_SERVER, serverId);
        }
        return PermissionContext.of(entries);
    }

    /** Package-private for tests — the configured server id, or null when world-only. */
    @Nullable String serverId() {
        return serverId;
    }

    private static void requireValidServerId(String serverId) {
        if (serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be blank; pass null for world-only contexts");
        }
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("serverId must be lowercase dns-ish (e.g. 'survival-1'), got: " + serverId);
        }
    }
}
