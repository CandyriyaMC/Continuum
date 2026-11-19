package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.common.config.KnownModules;

import java.util.List;
import java.util.Optional;

/**
 * The static catalog of first-party synchronization modules this build ships,
 * joined with the config toggle keys.
 *
 * <p>Three namespaces are in play and callers routinely mix them up, so the
 * catalog keeps them explicit: the API/snapshot namespace
 * ({@code continuum.inventory}, from {@link BuiltinModules}), the config
 * namespace used by {@code sync.modules} ({@code inventory}, from
 * {@link KnownModules}) and the {@code custom} namespace, which is reserved
 * for third-party adapters and carries no first-party entries. Entries with
 * no config key are always on; entries without a dotted id exist only in
 * config space. The proxy holds no live module registry - schema version,
 * priority and third-party adapters are backend-side facts.</p>
 */
final class ModuleCatalog {

    /**
     * One first-party module as the proxy knows it.
     *
     * @param id          the dotted API id, stable across releases
     * @param configKey   the {@code sync.modules.*} short key, or null when the module has no config toggle
     * @param inDefaultRestoreOrder true when the module appears in {@link KnownModules#DEFAULT_RESTORE_ORDER}
     */
    record BuiltinModule(String id, String configKey, boolean inDefaultRestoreOrder) {
    }

    private static final List<BuiltinModule> BUILTINS = List.of(
            new BuiltinModule(BuiltinModules.ATTRIBUTES, KnownModules.ATTRIBUTES, true),
            new BuiltinModule(BuiltinModules.VITALS, KnownModules.VITALS, true),
            new BuiltinModule(BuiltinModules.INVENTORY, KnownModules.INVENTORY, true),
            new BuiltinModule(BuiltinModules.ENDERCHEST, KnownModules.ENDER_CHEST, true),
            new BuiltinModule(BuiltinModules.EXPERIENCE, KnownModules.EXPERIENCE, true),
            new BuiltinModule(BuiltinModules.EFFECTS, KnownModules.EFFECTS, true),
            new BuiltinModule(BuiltinModules.LOCATION, KnownModules.LOCATION, true),
            new BuiltinModule(BuiltinModules.GAMEMODE, KnownModules.GAMEMODE, true),
            new BuiltinModule(BuiltinModules.ADVANCEMENTS, KnownModules.ADVANCEMENTS, true),
            new BuiltinModule(BuiltinModules.STATISTICS, KnownModules.STATISTICS, true),
            new BuiltinModule(BuiltinModules.RECIPES, KnownModules.RECIPES, true),
            new BuiltinModule(BuiltinModules.PDC, KnownModules.PDC, true),
            new BuiltinModule(BuiltinModules.METADATA, null, false),
            new BuiltinModule(BuiltinModules.PERMISSIONS, null, false));

    /** The {@code custom.*} namespace marker; not a module id and never registered. */
    static final String CUSTOM_NAMESPACE = "custom";

    private ModuleCatalog() {
    }

    /** All first-party modules in restore-order-agnostic declaration order. */
    static List<BuiltinModule> builtins() {
        return BUILTINS;
    }

    /** All dotted ids, for command suggestions. */
    static List<String> builtinIds() {
        return BUILTINS.stream().map(BuiltinModule::id).toList();
    }

    /**
     * Resolves {@code query} to a built-in module. Accepts the dotted id
     * ({@code continuum.inventory}), the short config key ({@code inventory})
     * and the short key with underscores where the catalog uses dashes
     * ({@code ender_chest}). Case-insensitive.
     */
    static Optional<BuiltinModule> resolve(String query) {
        if (query == null) {
            return Optional.empty();
        }
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        for (BuiltinModule module : BUILTINS) {
            if (module.id().equals(normalized)) {
                return Optional.of(module);
            }
        }
        // ender_chest -> enderchest is too lossy; normalize dashes consistently instead
        String spaced = normalized.replace('_', '-');
        String compact = spaced.replace("-", "");
        for (BuiltinModule module : BUILTINS) {
            if (module.configKey() != null
                    && (module.configKey().equals(spaced)
                    || module.configKey().replace("-", "").equals(compact))) {
                return Optional.of(module);
            }
            // bare suffix of the dotted id, dash-insensitive ("enderchest", "ender_chest")
            String idSuffix = module.id().substring("continuum.".length()).replace("-", "");
            if (idSuffix.equals(compact)) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }

    /**
     * True when the query refers to the third-party adapter namespace in any
     * of its spellings ({@code custom}, {@code custom.*}, {@code continuum.custom}).
     */
    static boolean isCustomNamespace(String query) {
        if (query == null) {
            return false;
        }
        String normalized = query.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals(CUSTOM_NAMESPACE)
                || normalized.startsWith(CUSTOM_NAMESPACE + ".");
    }
}
