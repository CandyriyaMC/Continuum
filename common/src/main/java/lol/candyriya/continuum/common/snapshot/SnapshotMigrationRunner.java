package lol.candyriya.continuum.common.snapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the ordered migration chain from a snapshot's stored schema version
 * up to a target version (normally {@link SnapshotSchema#CURRENT_VERSION}).
 *
 * <p>Chain rules, checked at construction: at most one migration per source
 * version, every step climbs exactly one version. Running the runner on a
 * target-version document is a no-op, which makes migrations naturally
 * idempotent: replaying the chain from an already-migrated file changes
 * nothing.</p>
 *
 * <p>Missing steps fail loudly with the gap named — silently accepting an
 * unmigrated document is how formats rot in production.</p>
 */
public final class SnapshotMigrationRunner {

    private final Map<Integer, SnapshotMigration> byFromVersion;
    private final int targetVersion;

    public SnapshotMigrationRunner(List<SnapshotMigration> migrations) {
        this(migrations, SnapshotSchema.CURRENT_VERSION);
    }

    /** Explicit target, for tests and for tooling that migrates to a newer schema on purpose. */
    public SnapshotMigrationRunner(List<SnapshotMigration> migrations, int targetVersion) {
        if (targetVersion < 1) {
            throw new SnapshotMigrationException("target version must be >= 1, got " + targetVersion);
        }
        var index = new HashMap<Integer, SnapshotMigration>();
        for (SnapshotMigration migration : migrations) {
            if (migration.toVersion() != migration.fromVersion() + 1) {
                throw new SnapshotMigrationException(
                        "migration " + migration.getClass().getSimpleName() + " does not climb exactly one version ("
                                + migration.fromVersion() + " -> " + migration.toVersion() + ")");
            }
            SnapshotMigration previous = index.put(migration.fromVersion(), migration);
            if (previous != null) {
                throw new SnapshotMigrationException(
                        "duplicate migration for schema version " + migration.fromVersion() + ": "
                                + previous.getClass().getSimpleName() + " and " + migration.getClass().getSimpleName());
            }
        }
        this.byFromVersion = Map.copyOf(index);
        this.targetVersion = targetVersion;
    }

    public int targetVersion() {
        return targetVersion;
    }

    /**
     * Migrates {@code raw} (a full snapshot document) to the target schema.
     *
     * @param raw parsed snapshot JSON; must carry an integer {@code schema_version}
     * @return migrated node at the target version
     */
    public JsonNode migrate(JsonNode raw) {
        JsonNode versionNode = raw.get("schema_version");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            throw new SnapshotMigrationException("snapshot document is missing an integer 'schema_version' field");
        }
        int version = versionNode.intValue();
        if (version < 1) {
            throw new SnapshotMigrationException("snapshot schema_version must be >= 1, got " + version);
        }
        if (version > targetVersion) {
            throw new SnapshotMigrationException(
                    "snapshot schema_version " + version + " is newer than this build supports ("
                            + targetVersion + "); refusing to guess");
        }

        JsonNode current = raw;
        while (version < targetVersion) {
            SnapshotMigration step = byFromVersion.get(version);
            if (step == null) {
                throw new SnapshotMigrationException(
                        "no migration registered from schema version " + version + " to " + (version + 1)
                                + "; cannot upgrade snapshot");
            }
            JsonNode upgraded = step.migrate(current);
            if (upgraded == null) {
                throw new SnapshotMigrationException(
                        "migration " + step.getClass().getSimpleName() + " returned null");
            }
            JsonNode upgradedVersion = upgraded.get("schema_version");
            if (upgradedVersion == null || !upgradedVersion.isIntegralNumber()
                    || upgradedVersion.intValue() != step.toVersion()) {
                throw new SnapshotMigrationException(
                        "migration " + step.getClass().getSimpleName() + " did not set schema_version to "
                                + step.toVersion());
            }
            current = upgraded;
            version = step.toVersion();
        }
        return current;
    }

    /** Helper for migration authors: set schema_version on the returned node. */
    public static JsonNode withSchemaVersion(JsonNode node, int version) {
        if (node instanceof tools.jackson.databind.node.ObjectNode objectNode) {
            objectNode.set("schema_version", IntNode.valueOf(version));
            return objectNode;
        }
        throw new SnapshotMigrationException("schema_version can only be set on a JSON object node");
    }
}
