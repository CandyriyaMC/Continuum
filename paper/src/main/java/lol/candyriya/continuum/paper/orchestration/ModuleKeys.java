package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;

import java.util.List;

/**
 * Maps the shared config's module ids to the ids persisted in snapshots.
 *
 * <p>The shared config speaks short kebab-case ids ({@code "ender-chest"}) because
 * they are operator-facing YAML keys; the snapshot schema speaks the dotted
 * {@code continuum.*} ids from {@link BuiltinModules} because those are the stable
 * wire contract. This one table is the bridge between the two — both the
 * {@code sync.modules.*} toggles and {@code sync.recovery.restore-order} go
 * through it.</p>
 */
final class ModuleKeys {

    private ModuleKeys() {
    }

    /** Short config id -> dotted snapshot id. Ids not listed here have no first-party module. */
    static String toDotted(String shortId) {
        return switch (shortId) {
            case "inventory" -> BuiltinModules.INVENTORY;
            case "ender-chest" -> BuiltinModules.ENDERCHEST;
            case "vitals" -> BuiltinModules.VITALS;
            case "experience" -> BuiltinModules.EXPERIENCE;
            case "effects" -> BuiltinModules.EFFECTS;
            case "attributes" -> BuiltinModules.ATTRIBUTES;
            case "location" -> BuiltinModules.LOCATION;
            case "gamemode" -> BuiltinModules.GAMEMODE;
            case "advancements" -> BuiltinModules.ADVANCEMENTS;
            case "statistics" -> BuiltinModules.STATISTICS;
            case "recipes" -> BuiltinModules.RECIPES;
            case "pdc" -> BuiltinModules.PDC;
            case "metadata" -> BuiltinModules.METADATA;
            case "permissions" -> BuiltinModules.PERMISSIONS;
            // reserved in the config namespace; no first-party module exists under this id yet
            case "custom" -> null;
            default -> null;
        };
    }

    /** Dotted snapshot id -> short config id; null when the module has no toggle key. */
    static String toShort(String dottedId) {
        return switch (dottedId) {
            case BuiltinModules.INVENTORY -> "inventory";
            case BuiltinModules.ENDERCHEST -> "ender-chest";
            case BuiltinModules.VITALS -> "vitals";
            case BuiltinModules.EXPERIENCE -> "experience";
            case BuiltinModules.EFFECTS -> "effects";
            case BuiltinModules.ATTRIBUTES -> "attributes";
            case BuiltinModules.LOCATION -> "location";
            case BuiltinModules.GAMEMODE -> "gamemode";
            case BuiltinModules.ADVANCEMENTS -> "advancements";
            case BuiltinModules.STATISTICS -> "statistics";
            case BuiltinModules.RECIPES -> "recipes";
            case BuiltinModules.PDC -> "pdc";
            case BuiltinModules.METADATA -> "metadata";
            case BuiltinModules.PERMISSIONS -> "permissions";
            default -> null;
        };
    }

    /** Translates a list of short config ids to dotted ids, dropping the unmappable ones. */
    static List<String> configuredOrderToDotted(List<String> shortIds) {
        return shortIds.stream()
                .map(ModuleKeys::toDotted)
                .filter(id -> id != null)
                .toList();
    }
}
