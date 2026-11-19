package lol.candyriya.continuum.api.snapshot;

import lol.candyriya.continuum.api.module.ModuleIds;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Metadata of a stored snapshot — everything history and diagnostics need to list, compare
 * and select snapshots, without revealing the per-module payloads.
 *
 * <p>The payload lives in the database and is deliberately not exposed through this type:
 * builds against it are not coupled to the internal serialization format.</p>
 *
 * <p>Instances are immutable. {@link #moduleVersions()} preserves capture order (the order
 * modules ran) and is unmodifiable.</p>
 *
 * @param snapshotId the database id of the snapshot, &ge; 1; rollbacks produce a new id
 * @param schemaVersion the snapshot-container schema version (not per-module — those live
 *        in {@link #moduleVersions()}), &ge; 1
 * @param playerId the player whose state this is, never null
 * @param createdAt when the snapshot was taken, never null
 * @param backendId the backend that captured it (server name as configured), never null, never blank
 * @param fencingToken the fencing token the snapshot was written under, &ge; 0; a snapshot
 *        can only be written when the token is the current one for the player's session
 * @param moduleVersions module id to the schema version that produced the payload,
 *        capture order; never null, copied — unmodifiable; keys well-formed module ids,
 *        values &ge; 1
 * @param reason why the snapshot was taken, never null
 * @param result the overall verdict of the capture, never null
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public record PlayerSnapshot(
        long snapshotId,
        int schemaVersion,
        @NotNull UUID playerId,
        @NotNull Instant createdAt,
        @NotNull String backendId,
        long fencingToken,
        @NotNull Map<String, Integer> moduleVersions,
        @NotNull SnapshotReason reason,
        @NotNull SyncResult result) {

    /**
     * Canonical constructor with validation and normalization.
     *
     * @throws NullPointerException when any {@code @NotNull} component is null
     * @throws IllegalArgumentException when {@code snapshotId} or {@code schemaVersion} is
     *         below 1, when {@code backendId} is blank, when {@code fencingToken} is negative,
     *         or when a module version is below 1
     * @throws lol.candyriya.continuum.api.module.InvalidModuleIdException when a module key is not a well-formed module id
     */
    public PlayerSnapshot {
        if (snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1, got " + snapshotId);
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
        }
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(backendId, "backendId");
        if (backendId.isBlank()) {
            throw new IllegalArgumentException("backendId must not be blank");
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        Objects.requireNonNull(moduleVersions, "moduleVersions");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(result, "result");
        var copy = new LinkedHashMap<String, Integer>(moduleVersions.size());
        for (var entry : moduleVersions.entrySet()) {
            String moduleId = entry.getKey();
            Integer moduleVersion = entry.getValue();
            if (moduleId == null) {
                throw new NullPointerException("moduleVersions must not contain null keys");
            }
            ModuleIds.requireValid(moduleId);
            if (moduleVersion == null) {
                throw new NullPointerException("moduleVersions must not contain null values for '" + moduleId + "'");
            }
            if (moduleVersion < 1) {
                throw new IllegalArgumentException(
                        "moduleVersions must map to versions >= 1, got " + moduleVersion + " for '" + moduleId + "'");
            }
            copy.put(moduleId, moduleVersion);
        }
        moduleVersions = Collections.unmodifiableMap(copy);
    }

    /**
     * The schema version stored for one module, if it participated in the capture.
     *
     * @param moduleId the module id; must not be null
     * @return the version that wrote the payload, or empty when the module did not participate
     * @throws NullPointerException when {@code moduleId} is null
     */
    @NotNull
    public Optional<Integer> moduleVersion(@NotNull String moduleId) {
        Objects.requireNonNull(moduleId, "moduleId");
        return Optional.ofNullable(moduleVersions.get(moduleId));
    }

    /**
     * The number of modules in the snapshot.
     *
     * @return the module count, &ge; 0
     */
    public int moduleCount() {
        return moduleVersions.size();
    }
}
