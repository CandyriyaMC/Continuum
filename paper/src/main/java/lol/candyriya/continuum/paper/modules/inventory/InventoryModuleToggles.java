package lol.candyriya.continuum.paper.modules.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.KnownModules;
import lol.candyriya.continuum.common.config.SyncConfig;

/**
 * Maps the {@code sync.modules.*} config toggles (short ids from {@code KnownModules}) to the
 * first-party inventory modules and their persisted ids (from {@code BuiltinModules}).
 *
 * <p>Honesty about wiring: a {@code SyncModule} must behave deterministically once registered —
 * flipping its behavior mid-session based on a live config read would be worse than not
 * registering it. So enablement is a <em>registration-time</em> decision: the platform's module
 * wiring calls {@link #inventoryEnabled} / {@link #enderChestEnabled} (or takes the id list from
 * {@link #enabledModuleIds}) and simply constructs only the enabled modules. The modules
 * themselves are toggle-agnostic.</p>
 *
 * <p>Unknown keys in the config are rejected at load time by {@code ConfigLoader}, so a typo
 * cannot silently disable a module.</p>
 */
public final class InventoryModuleToggles {

    private InventoryModuleToggles() {
        // static access only
    }

    /** Whether the inventory module ({@code continuum.inventory}) is enabled; defaults to true. */
    public static boolean inventoryEnabled(SyncConfig config) {
        return toggle(config, KnownModules.INVENTORY);
    }

    /** Whether the ender chest module ({@code continuum.enderchest}) is enabled; defaults to true. */
    public static boolean enderChestEnabled(SyncConfig config) {
        return toggle(config, KnownModules.ENDER_CHEST);
    }

    /** The persisted module ids to register, in canonical order. Empty when both toggles are off. */
    public static List<String> enabledModuleIds(SyncConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        var ids = new ArrayList<String>(2);
        if (inventoryEnabled(config)) {
            ids.add(BuiltinModules.INVENTORY);
        }
        if (enderChestEnabled(config)) {
            ids.add(BuiltinModules.ENDERCHEST);
        }
        return List.copyOf(ids);
    }

    private static boolean toggle(SyncConfig config, String key) {
        Objects.requireNonNull(config, "config must not be null");
        // missing keys default to enabled — first-party modules are opt-out, mirroring the config's own defaulting
        return config.modules().getOrDefault(key, true);
    }
}
