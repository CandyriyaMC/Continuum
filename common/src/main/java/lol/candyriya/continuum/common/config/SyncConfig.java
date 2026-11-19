package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.List;
import java.util.Map;

/**
 * Sync behavior: autosave, per-module toggles, transfers and recovery.
 *
 * @param autosave  periodic snapshotting
 * @param modules   module id -> enabled; missing known ids default to {@code true},
 *                  unknown ids are rejected at load time
 * @param transfer  transfer behavior toggles
 * @param recovery  automatic recovery sweeps
 */
@ConfigSerializable
public record SyncConfig(AutosaveConfig autosave, Map<String, Boolean> modules, TransferConfig transfer, RecoveryConfig recovery) {

    public SyncConfig {
        if (autosave == null) {
            autosave = new AutosaveConfig(true, java.time.Duration.ofSeconds(60));
        }
        if (modules != null) {
            for (Map.Entry<String, Boolean> entry : modules.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("sync.modules." + entry.getKey() + ": must be true or false, got null");
                }
            }
            modules = Map.copyOf(modules);
        } else {
            modules = Map.of();
        }
        if (transfer == null) {
            transfer = new TransferConfig(true, true);
        }
        if (recovery == null) {
            recovery = new RecoveryConfig(true, java.time.Duration.ofSeconds(30), List.of());
        }
    }

    /** All known module ids with the effective toggle state (defaults filled in). */
    public Map<String, Boolean> modulesWithDefaults() {
        var merged = new java.util.LinkedHashMap<String, Boolean>();
        for (String id : KnownModules.ALL) {
            merged.put(id, modules.getOrDefault(id, true));
        }
        return Map.copyOf(merged);
    }
}
