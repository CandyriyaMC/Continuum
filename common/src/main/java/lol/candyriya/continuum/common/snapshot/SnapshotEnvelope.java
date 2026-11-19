package lol.candyriya.continuum.common.snapshot;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Top-level snapshot container: the unit of storage and of transfer.
 *
 * @param schemaVersion schema of this envelope, see {@link SnapshotSchema}
 * @param playerId      owning player
 * @param createdAt     capture time (epoch-milli on the wire)
 * @param modules       per-module payloads; module ids must be unique inside one snapshot
 */
public record SnapshotEnvelope(int schemaVersion, UUID playerId, Instant createdAt, List<ModuleSnapshot> modules) {

    public SnapshotEnvelope {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
        }
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        modules = modules == null ? List.of() : List.copyOf(modules);
        var seen = new HashSet<String>();
        for (ModuleSnapshot module : modules) {
            Objects.requireNonNull(module, "modules must not contain null entries");
            if (!seen.add(module.moduleId())) {
                throw new IllegalArgumentException("duplicate module in snapshot: " + module.moduleId());
            }
        }
    }
}
