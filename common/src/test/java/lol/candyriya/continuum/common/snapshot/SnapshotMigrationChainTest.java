package lol.candyriya.continuum.common.snapshot;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The long-chain migration case: a realistic 30-step upgrade chain exercised
 * end to end — in-order application with cumulative effects, shuffled
 * registration, replay idempotency, per-step gap detection and
 * future-version rejection.
 */
class SnapshotMigrationChainTest {

    private static final int CHAIN_LENGTH = 30;

    private final tools.jackson.databind.json.JsonMapper mapper =
            lol.candyriya.continuum.common.internal.JacksonMappers.create();

    private JsonNode doc(int version) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("schema_version", version);
        doc.put("player_id", "player-under-test");
        // every step appends its source version; the final array must read 1..30 in order
        doc.putArray("steps");
        return doc;
    }

    /** A step that appends its from-version to the "steps" array — so order and completeness are observable. */
    private static SnapshotMigration trackingStep(int from) {
        return new SnapshotMigration() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public JsonNode migrate(JsonNode snapshot) {
                ObjectNode object = (ObjectNode) snapshot.deepCopy();
                ArrayNode steps = (ArrayNode) object.get("steps");
                steps.add(from);
                return SnapshotMigrationRunner.withSchemaVersion(object, toVersion());
            }
        };
    }

    private static List<SnapshotMigration> chain(int length) {
        List<SnapshotMigration> steps = new ArrayList<>();
        for (int i = 1; i <= length; i++) {
            steps.add(trackingStep(i));
        }
        return steps;
    }

    @Test
    void thirtyStepChainAppliesInOrderAndReachesTheTarget() {
        var runner = new SnapshotMigrationRunner(chain(CHAIN_LENGTH), CHAIN_LENGTH + 1);

        JsonNode migrated = runner.migrate(doc(1));

        assertEquals(CHAIN_LENGTH + 1, migrated.get("schema_version").asInt());
        List<Integer> visited = new ArrayList<>();
        migrated.get("steps").forEach(node -> visited.add(node.asInt()));
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= CHAIN_LENGTH; i++) {
            expected.add(i);
        }
        assertEquals(expected, visited, "every step must fire exactly once, in chain order");
    }

    @Test
    void registrationOrderIsIrrelevantToTheChain() {
        List<SnapshotMigration> shuffled = chain(CHAIN_LENGTH);
        // deterministic shuffle — same seed, same order, reproducible run
        Collections.shuffle(shuffled, new Random(20261119L));

        var runner = new SnapshotMigrationRunner(shuffled, CHAIN_LENGTH + 1);
        JsonNode migrated = runner.migrate(doc(1));

        assertEquals(CHAIN_LENGTH + 1, migrated.get("schema_version").asInt());
        assertEquals(CHAIN_LENGTH, migrated.get("steps").size());
        assertEquals(CHAIN_LENGTH, migrated.get("steps").get(CHAIN_LENGTH - 1).asInt());
    }

    @Test
    void thirtyStepChainIsIdempotentUnderReplay() {
        var runner = new SnapshotMigrationRunner(chain(CHAIN_LENGTH), CHAIN_LENGTH + 1);

        JsonNode once = runner.migrate(doc(1));
        JsonNode twice = runner.migrate(once);
        JsonNode thrice = runner.migrate(twice);

        assertEquals(once, twice);
        assertEquals(twice, thrice);
        assertEquals(CHAIN_LENGTH, twice.get("steps").size(), "replay must not re-apply any step");
    }

    @Test
    void everyGapInTheChainIsRejectedAndNamesTheMissingVersion() {
        for (int missing = 1; missing <= CHAIN_LENGTH; missing++) {
            List<SnapshotMigration> withGap = new ArrayList<>(chain(CHAIN_LENGTH));
            int finalMissing = missing;
            withGap.removeIf(step -> step.fromVersion() == finalMissing);
            var runner = new SnapshotMigrationRunner(withGap, CHAIN_LENGTH + 1);

            SnapshotMigrationException e = assertThrows(SnapshotMigrationException.class,
                    () -> runner.migrate(doc(1)),
                    "gap at version " + missing + " must abort the chain");
            assertTrue(e.getMessage().contains(String.valueOf(missing)),
                    "error must name the missing step " + missing + ", got: " + e.getMessage());
        }
    }

    @Test
    void documentFromTheFutureIsRejected() {
        var runner = new SnapshotMigrationRunner(chain(CHAIN_LENGTH), CHAIN_LENGTH + 1);

        SnapshotMigrationException e = assertThrows(SnapshotMigrationException.class,
                () -> runner.migrate(doc(CHAIN_LENGTH + 2)));

        assertTrue(e.getMessage().contains("newer"), e.getMessage());
    }

    @Test
    void partialDocumentMidwayThroughTheChainResumesFromItsStoredVersion() {
        var runner = new SnapshotMigrationRunner(chain(CHAIN_LENGTH), CHAIN_LENGTH + 1);

        // a document that already carries the first 15 steps must continue from 16, not restart
        JsonNode midway = runner.migrate(doc(1));
        // hand-craft a v16 document as if an old rollout had stopped there
        ObjectNode v16 = (ObjectNode) midway.deepCopy();
        v16.put("schema_version", 16);
        ArrayNode steps16 = mapper.createArrayNode();
        for (int i = 1; i <= 15; i++) {
            steps16.add(i);
        }
        v16.set("steps", steps16);

        JsonNode finished = runner.migrate(v16);

        assertEquals(CHAIN_LENGTH + 1, finished.get("schema_version").asInt());
        assertEquals(CHAIN_LENGTH, finished.get("steps").size());
        assertEquals(16, finished.get("steps").get(15).asInt());
        assertEquals(CHAIN_LENGTH, finished.get("steps").get(CHAIN_LENGTH - 1).asInt());
    }
}
