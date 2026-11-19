package lol.candyriya.continuum.common.snapshot;

import tools.jackson.databind.JsonNode;

/**
 * One step of the snapshot migration chain: schema {@code fromVersion()} to
 * {@code toVersion()} (always {@code fromVersion() + 1}).
 *
 * <p>Migrations are pure tree surgery: receive the raw snapshot node, return
 * the upgraded node. They run inside {@link SnapshotMigrationRunner} and see
 * no DTO types on either side — that keeps old-schema documents replayable
 * forever, even after the DTO classes they once described are gone.</p>
 */
public interface SnapshotMigration {

    int fromVersion();

    /** Always {@code fromVersion() + 1}; enforced by the runner. */
    default int toVersion() {
        return fromVersion() + 1;
    }

    /** @return upgraded node; must set {@code schema_version} to {@link #toVersion()} */
    JsonNode migrate(JsonNode snapshot);
}
