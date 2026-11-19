package lol.candyriya.continuum.common.snapshot;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotMigrationRunnerTest {

    private final tools.jackson.databind.json.JsonMapper mapper =
            lol.candyriya.continuum.common.internal.JacksonMappers.create();

    private JsonNode doc(int version) {
        try {
            return mapper.readTree("{\"schema_version\":" + version + ",\"player_id\":\"p\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SnapshotMigration step(int from, String tag, List<String> log) {
        return new SnapshotMigration() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public JsonNode migrate(JsonNode snapshot) {
                log.add(tag);
                return SnapshotMigrationRunner.withSchemaVersion(snapshot.deepCopy(), toVersion());
            }
        };
    }

    @Test
    void migrationsApplyInOrder() {
        List<String> log = new ArrayList<>();
        var runner = new SnapshotMigrationRunner(
                List.of(step(2, "second", log), step(1, "first", log)), 3);
        JsonNode migrated = runner.migrate(doc(1));
        assertEquals(List.of("first", "second"), log);
        assertEquals(3, migrated.get("schema_version").asInt());
    }

    @Test
    void documentAtTargetVersionIsANoOp() {
        List<String> log = new ArrayList<>();
        var runner = new SnapshotMigrationRunner(List.of(step(1, "first", log)), 2);
        JsonNode migrated = runner.migrate(doc(2));
        assertEquals(List.of(), log);
        assertEquals(2, migrated.get("schema_version").asInt());
    }

    @Test
    void rerunningIsIdempotent() {
        List<String> log = new ArrayList<>();
        var runner = new SnapshotMigrationRunner(List.of(step(1, "first", log)), 2);
        JsonNode once = runner.migrate(doc(1));
        JsonNode twice = runner.migrate(once);
        assertEquals(once, twice);
        assertEquals(List.of("first"), log, "second pass must not re-apply the step");
    }

    @Test
    void missingStepFailsWithGapNamed() {
        var runner = new SnapshotMigrationRunner(List.of(step(1, "first", new ArrayList<>())), 3);
        SnapshotMigrationException e = assertThrows(SnapshotMigrationException.class, () -> runner.migrate(doc(1)));
        assertTrue(e.getMessage().contains("2"), e.getMessage());
        assertEquals("serialization.migration-failed", e.code());
    }

    @Test
    void duplicateSourceVersionRejectedAtConstruction() {
        List<String> log = new ArrayList<>();
        SnapshotMigration first = step(1, "a", log);
        SnapshotMigration second = step(1, "b", log);
        assertThrows(SnapshotMigrationException.class,
                () -> new SnapshotMigrationRunner(List.of(first, second), 2));
    }

    @Test
    void climbBeyondOneVersionIsRejectedAtConstruction() {
        SnapshotMigration jumpy = new SnapshotMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public int toVersion() {
                return 3;
            }

            @Override
            public JsonNode migrate(JsonNode snapshot) {
                return snapshot;
            }
        };
        assertThrows(SnapshotMigrationException.class,
                () -> new SnapshotMigrationRunner(List.of(jumpy), 3));
    }

    @Test
    void stepNotSettingSchemaVersionYieldsClearError() {
        SnapshotMigration lazy = new SnapshotMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public JsonNode migrate(JsonNode snapshot) {
                return snapshot; // forgot to bump
            }
        };
        var runner = new SnapshotMigrationRunner(List.of(lazy), 2);
        SnapshotMigrationException e = assertThrows(SnapshotMigrationException.class, () -> runner.migrate(doc(1)));
        assertTrue(e.getMessage().contains("schema_version"));
    }

    @Test
    void stepReturningNullYieldsClearError() {
        SnapshotMigration nullStep = new SnapshotMigration() {
            @Override
            public int fromVersion() {
                return 1;
            }

            @Override
            public JsonNode migrate(JsonNode snapshot) {
                return null;
            }
        };
        var runner = new SnapshotMigrationRunner(List.of(nullStep), 2);
        assertThrows(SnapshotMigrationException.class, () -> runner.migrate(doc(1)));
    }

    @Test
    void newerThanTargetVersionIsRejected() {
        var runner = new SnapshotMigrationRunner(List.of(), 1);
        SnapshotMigrationException e = assertThrows(SnapshotMigrationException.class, () -> runner.migrate(doc(2)));
        assertTrue(e.getMessage().contains("newer"));
    }

    @Test
    void missingSchemaVersionFieldIsRejected() {
        var runner = new SnapshotMigrationRunner(List.of(), 1);
        assertThrows(SnapshotMigrationException.class,
                () -> runner.migrate(mapper.createObjectNode()));
    }

    @Test
    void zeroOrNegativeSchemaVersionIsRejected() {
        var runner = new SnapshotMigrationRunner(List.of(), 1);
        assertThrows(SnapshotMigrationException.class, () -> runner.migrate(doc(0)));
    }

    @Test
    void targetVersionMustBePositive() {
        assertThrows(SnapshotMigrationException.class, () -> new SnapshotMigrationRunner(List.of(), 0));
    }
}
