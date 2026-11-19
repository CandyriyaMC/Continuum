package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

import org.jetbrains.annotations.Nullable;

/**
 * The platform seam for this module: the two name surfaces the module is
 * allowed to touch, in plain Java. Implemented over {@code org.bukkit.entity.Player}
 * by {@link BukkitProfileOps}; unit tests use their own fake.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>{@link #displayName()} and {@link #listName()} may return {@code null} —
 *       both Paper getters document absence as possible/undefined.</li>
 *   <li>{@link #setDisplayName(Component)} and {@link #setListName(Component)}
 *       accept {@code null} to reset the target to the vanilla default
 *       (plain player name); both Paper setters are annotated
 *       {@code @Nullable} for exactly this.</li>
 * </ul>
 */
public interface ProfileOps {

    /** The player's real (undecorated) name — the normalization baseline for "never customized". */
    String playerName();

    /** The custom display name, or {@code null} when the platform reports none. */
    @Nullable Component displayName();

    /** The custom tab-list name, or {@code null} when the platform reports none. */
    @Nullable Component listName();

    /** Set the display name; {@code null} resets to the vanilla default. */
    void setDisplayName(@Nullable Component displayName);

    /** Set the tab-list name; {@code null} resets to the plain player name. */
    void setListName(@Nullable Component playerListName);
}
