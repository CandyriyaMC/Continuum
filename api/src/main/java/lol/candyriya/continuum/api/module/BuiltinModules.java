package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;

/**
 * Ids of Continuum's first-party modules. These are stable, persisted in snapshots and
 * used in dependency declarations ({@link SyncModule#dependencies()}), so they are
 * permanent: third-party modules may depend on them, and must not register ids in this
 * namespace.
 *
 * <p>Restoring order is roughly the order of the concepts in the player's experience of
 * logging in (attributes before vitals — max health must be raised first), but callers
 * should always express real requirements through {@link SyncModule#dependencies()} rather
 * than rely on this list.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class BuiltinModules {

    /** Inventory contents and armor. */
    public static final String INVENTORY = "continuum.inventory";

    /** Ender chest contents. */
    public static final String ENDERCHEST = "continuum.enderchest";

    /** Health, food and saturation. */
    public static final String VITALS = "continuum.vitals";

    /** Experience level, progress and total. */
    public static final String EXPERIENCE = "continuum.experience";

    /** Active potion effects, including duration and amplifier. */
    public static final String EFFECTS = "continuum.effects";

    /** Base attribute modifiers (max health, attack speed, ...). */
    public static final String ATTRIBUTES = "continuum.attributes";

    /** Position and orientation. */
    public static final String LOCATION = "continuum.location";

    /** Game mode (and spectator/creative flags that travel with it). */
    public static final String GAMEMODE = "continuum.gamemode";

    /** Advancement achievements and progress. */
    public static final String ADVANCEMENTS = "continuum.advancements";

    /** Accumulated statistics (delta-tracked, see the statistics module contract). */
    public static final String STATISTICS = "continuum.statistics";

    /** Known recipes. */
    public static final String RECIPES = "continuum.recipes";

    /** PersistentDataContainer of the player entity. */
    public static final String PDC = "continuum.pdc";

    /** Free-form metadata attached by the server or other integrations. */
    public static final String METADATA = "continuum.metadata";

    /** Permission-related state synced by the permissions integration. */
    public static final String PERMISSIONS = "continuum.permissions";

    private BuiltinModules() {
        // constants only
    }
}
