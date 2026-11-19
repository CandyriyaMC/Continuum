package lol.candyriya.continuum.common.config;

import java.util.List;

/**
 * Ids of every module this build knows how to sync, plus the default restore
 * order (cheapest/most fundamental first, cosmetic last).
 *
 * <p>The set is deliberately closed: unknown module ids in the config are
 * almost always typos, and silently accepting them would produce a network
 * where one server quietly syncs nothing.</p>
 */
public final class KnownModules {

    public static final String INVENTORY = "inventory";
    public static final String ENDER_CHEST = "ender-chest";
    public static final String VITALS = "vitals";
    public static final String EXPERIENCE = "experience";
    public static final String EFFECTS = "effects";
    public static final String ATTRIBUTES = "attributes";
    public static final String LOCATION = "location";
    public static final String GAMEMODE = "gamemode";
    public static final String ADVANCEMENTS = "advancements";
    public static final String STATISTICS = "statistics";
    public static final String RECIPES = "recipes";
    public static final String PDC = "pdc";
    public static final String CUSTOM = "custom";

    /** Every known module id, in default restore order. */
    public static final List<String> ALL = List.of(
            INVENTORY, ENDER_CHEST, VITALS, EXPERIENCE, EFFECTS,
            ATTRIBUTES, LOCATION, GAMEMODE, ADVANCEMENTS, STATISTICS,
            RECIPES, PDC, CUSTOM
    );

    /** Restore order used when the config does not specify one. */
    public static final List<String> DEFAULT_RESTORE_ORDER = ALL;

    private KnownModules() {
    }

    public static boolean isKnown(String id) {
        return ALL.contains(id);
    }
}
