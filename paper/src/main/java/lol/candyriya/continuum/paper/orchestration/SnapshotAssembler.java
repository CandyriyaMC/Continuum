package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.SnapshotSchema;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Assembles a snapshot envelope from freshly captured payloads plus the
 * last-known-good state.
 *
 * <p>Storage stores one envelope per player and replaces it wholesale, so a
 * capture that persists only a subset of modules would <em>erase</em> the rest.
 * This is where incremental saves stay lossless: modules that were not
 * captured this round are carried over from the last-known-good envelope, as
 * long as they are still enabled. Disabled modules are dropped from the write
 * even if present in the last-known-good envelope.</p>
 *
 * <p>The module list in the produced envelope is ordered by
 * {@code enabledModuleIds} (plan order) for determinism.</p>
 */
public final class SnapshotAssembler {

    /** One freshly captured module payload, ready to embed. */
    public record CapturedPayload(String moduleId, int moduleVersion, JsonNode payload) {
        public CapturedPayload {
            Objects.requireNonNull(moduleId, "moduleId");
            Objects.requireNonNull(payload, "payload");
            if (moduleVersion < 1) {
                throw new IllegalArgumentException("moduleVersion must be >= 1, got " + moduleVersion);
            }
        }
    }

    private SnapshotAssembler() {
    }

    /**
     * Merges the freshly captured payloads with {@code lkg} (when present).
     *
     * @return the envelope to persist, or null when there is nothing to write
     *         (no captured payloads and no reusable last-known-good entries)
     */
    public static SnapshotEnvelope merge(UUID playerId,
                                         Instant createdAt,
                                         List<String> enabledModuleIds,
                                         List<CapturedPayload> captured,
                                         SnapshotEnvelope lkg) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(enabledModuleIds, "enabledModuleIds");
        Objects.requireNonNull(captured, "captured");

        LinkedHashMap<String, ModuleSnapshot> byId = new LinkedHashMap<>();
        if (lkg != null) {
            for (ModuleSnapshot module : lkg.modules()) {
                if (enabledModuleIds.contains(module.moduleId())) {
                    byId.put(module.moduleId(), module);
                }
            }
        }
        for (CapturedPayload fresh : captured) {
            if (!enabledModuleIds.contains(fresh.moduleId())) {
                // defensive: the capture pass only produces enabled modules anyway
                continue;
            }
            byId.put(fresh.moduleId(),
                    new ModuleSnapshot(fresh.moduleId(), fresh.moduleVersion(), fresh.payload()));
        }
        if (byId.isEmpty()) {
            return null;
        }

        List<ModuleSnapshot> ordered = new ArrayList<>(byId.size());
        for (String id : enabledModuleIds) {
            ModuleSnapshot module = byId.get(id);
            if (module != null) {
                ordered.add(module);
            }
        }
        return new SnapshotEnvelope(SnapshotSchema.CURRENT_VERSION, playerId, createdAt, ordered);
    }
}
