package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.common.config.MongoPool;
import lol.candyriya.continuum.common.config.MongoSettings;
import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.PlayerSnapshot;
import lol.candyriya.continuum.storage.api.PlayerStats;
import lol.candyriya.continuum.storage.api.SaveOptions;
import lol.candyriya.continuum.storage.api.SaveResult;
import lol.candyriya.continuum.storage.api.SessionRecord;
import lol.candyriya.continuum.storage.api.SnapshotSummary;
import lol.candyriya.continuum.storage.api.StorageConfig;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared integration coverage against a real MongoDB replica set (Testcontainers).
 * Requires docker; the concrete subclass skips the whole class cleanly when
 * the docker daemon cannot be reached.
 *
 * <p>Tests never assume a clean database beyond "bootstrap has run": every
 * test uses fresh player UUIDs and either drains or filters the outbox.</p>
 *
 * <p>Note on determinism: the replica set is a single node, so majority reads
 * see everything it writes. The transaction machinery itself (write conflict
 * retry, snapshot isolation) is what the concurrency tests exercise.</p>
 */
abstract class AbstractMongoStorageIntegrationTest {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final SerializationConfig SERIALIZATION = new SerializationConfig(false, 4 * 1024 * 1024);
    private static final StorageConfig STORAGE = StorageConfig.defaults();
    private static final MongoPool POOL = new MongoPool(8, 8, Duration.ofSeconds(2));

    /** Connection string of the running replica set. */
    protected abstract String uri();

    /** Database to use inside the container. */
    protected abstract String database();

    protected MongoPlayerStorage openStorage() {
        return openStorage(STORAGE, SERIALIZATION);
    }

    protected MongoPlayerStorage openStorage(StorageConfig config, SerializationConfig serialization) {
        MongoSettings settings = new MongoSettings(uri(), database(), POOL);
        return MongoPlayerStorage.open(settings, serialization, config);
    }

    /** Opens a short-lived raw client for assertions and poisons; caller closes it. */
    protected MongoClient rawClient() {
        return MongoClients.create(uri());
    }

    protected List<String> rawIndexNames(String collection) {
        try (MongoClient raw = rawClient()) {
            MongoDatabase db = raw.getDatabase(database());
            return db.getCollection(collection).listIndexes()
                    .into(new ArrayList<>()).stream()
                    .map(doc -> doc.getString("name"))
                    .collect(Collectors.toList());
        }
    }

    protected boolean rawIndexUnique(String collection, String indexName) {
        try (MongoClient raw = rawClient()) {
            MongoDatabase db = raw.getDatabase(database());
            for (Document index : db.getCollection(collection).listIndexes().into(new ArrayList<>())) {
                if (indexName.equals(index.getString("name"))) {
                    return Boolean.TRUE.equals(index.getBoolean("unique"));
                }
            }
            return false;
        }
    }

    /** Reads one counter document, or null when the bootstrap did not seed it. */
    protected Document rawCounter(String key) {
        try (MongoClient raw = rawClient()) {
            return raw.getDatabase(database()).getCollection(MongoCollections.SNAPSHOT_COUNTERS)
                    .find(eq("_id", key)).first();
        }
    }

    private static SnapshotEnvelope freshEnvelope(UUID player, String moduleId, String marker) {
        JsonNode payload = MAPPER.readTree("{\"marker\":\"" + marker + "\"}");
        return new SnapshotEnvelope(1, player, Instant.now(),
                List.of(new ModuleSnapshot(moduleId, 1, payload)));
    }

    private static PlayerSnapshot snapshotFor(UUID player, String moduleId, String marker) {
        return new PlayerSnapshot(freshEnvelope(player, moduleId, marker), 0, Instant.now());
    }

    private static String markerOf(PlayerSnapshot snapshot) {
        return snapshot.envelope().modules().get(0).payload().get("marker").asText();
    }

    // --- boot / bootstrap ---------------------------------------------------------

    @Test
    void bootCreatesEveryExpectedIndexAndCounters() {
        try (MongoPlayerStorage storage = openStorage()) {
            assertNotNull(storage);
        }
        for (var entry : MongoSchemaMigrations.EXPECTED_INDEXES.entrySet()) {
            var expected = entry.getValue();
            assertTrue(rawIndexNames(expected.collection()).contains(entry.getKey()),
                    "missing index " + entry.getKey() + " on " + expected.collection());
            if (expected.unique()) {
                assertTrue(rawIndexUnique(expected.collection(), entry.getKey()),
                        "index " + entry.getKey() + " must be unique");
            }
        }
        for (String key : MongoCollections.COUNTER_KEYS) {
            Document counter = rawCounter(key);
            assertNotNull(counter, "counter '" + key + "' must be seeded");
            assertTrue(counter.get("value") instanceof Number, "counter '" + key + "' must be numeric");
            assertTrue(((Number) counter.get("value")).longValue() >= 0, "counter '" + key + "' must be >= 0");
        }
    }

    @Test
    void reopeningStorageIsIdempotent() {
        try (MongoPlayerStorage first = openStorage()) {
            // first boot seeded and validated everything; nothing to do
        }
        try (MongoPlayerStorage second = openStorage()) {
            // surviving to here means the validation passed on an already-prepared database
        }
    }

    @Test
    void counterSurvivesReopenAndFenceKeepsRising() {
        UUID player = UUID.randomUUID();
        long observed;
        try (MongoPlayerStorage storage = openStorage()) {
            observed = storage.acquireSession(player, "survival-1").token();
        }
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(UUID.randomUUID(), "survival-1");
            assertTrue(session.token() > observed, "a fresh process must never re-issue a lower token");
        }
    }

    // --- fencing ---------------------------------------------------------------

    @Test
    void fencingTokensAreGloballyMonotonic() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord a = storage.acquireSession(first, "survival-1");
            SessionRecord b = storage.acquireSession(second, "survival-2");
            SessionRecord c = storage.acquireSession(first, "survival-1");
            assertTrue(b.token() > a.token(), "independent players must still get increasing tokens");
            assertTrue(c.token() > b.token(), "tokens must never repeat or go backwards");
        }
    }

    @Test
    void racingFirstWritersConvergeOnTheMaxToken() throws Exception {
        UUID player = UUID.randomUUID();
        int threads = 8;
        long base = 10_000L;
        ConcurrentLinkedQueue<SaveResult> results = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long token = base + i;
            String marker = "marker-" + token;
            Thread worker = Thread.ofPlatform().name("first-write-" + i).start(() -> {
                try (MongoPlayerStorage storage = openStorage()) {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    results.add(storage.save(snapshotFor(player, "inventory", marker), token, SaveOptions.of("AUTOSAVE", "test")));
                } catch (Exception e) {
                    throw new AssertionError("concurrent save failed", e);
                }
            });
            workers.add(worker);
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        long maxToken = base + threads - 1;
        try (MongoPlayerStorage storage = openStorage()) {
            Optional<PlayerSnapshot> stored = storage.load(player);
            assertTrue(stored.isPresent());
            assertEquals(maxToken, stored.get().fencingToken(), "the highest racing token must win");
            assertEquals("marker-" + maxToken, markerOf(stored.get()), "stored payload must be the winner's");
        }
        long saved = results.stream().filter(SaveResult::saved).count();
        assertTrue(saved >= 1, "at least one racing writer must succeed");
        for (SaveResult result : results) {
            if (result instanceof SaveResult.StaleToken stale) {
                assertTrue(stale.currentFencingToken() <= maxToken);
            }
        }
    }

    @Test
    void writesWithLowerTokenAreRejectedAfterNewerWrite() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            assertTrue(storage.save(snapshotFor(player, "vitals", "keep"), session.token(), SaveOptions.of("AUTOSAVE", "survival-1")).saved());

            SaveResult result = storage.save(snapshotFor(player, "vitals", "stale"), session.token() - 1, SaveOptions.of("AUTOSAVE", "survival-1"));
            assertTrue(result instanceof SaveResult.StaleToken, "expected StaleToken, got " + result);
            assertEquals(session.token(), ((SaveResult.StaleToken) result).currentFencingToken());
            assertEquals("keep", markerOf(storage.load(player).orElseThrow()), "stale write must not touch the row");
        }
    }

    @Test
    void writeWithOlderSessionTokenAfterReacquireIsRejected() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord first = storage.acquireSession(player, "survival-1");
            SessionRecord second = storage.acquireSession(player, "survival-2");
            SessionRecord winner = second;
            assertTrue(storage.save(snapshotFor(player, "inventory", "new"), winner.token(), SaveOptions.of("AUTOSAVE", "survival-2")).saved());

            SaveResult stale = storage.save(snapshotFor(player, "inventory", "old"), first.token(), SaveOptions.of("AUTOSAVE", "survival-1"));
            assertTrue(stale instanceof SaveResult.StaleToken, "a superseded token must not write");
            assertEquals(winner.token(), ((SaveResult.StaleToken) stale).currentFencingToken());
        }
    }

    @Test
    void firstWriteWithTokenZeroIsAcceptedAndMonotonicRewritesWin() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SaveResult first = storage.save(snapshotFor(player, "vitals", "v1"), 0, SaveOptions.of("API", "test"));
            assertTrue(first.saved());
            SaveResult second = storage.save(snapshotFor(player, "vitals", "v2"), 5, SaveOptions.of("API", "test"));
            assertTrue(second.saved(), "a higher token must overwrite");
            SaveResult losing = storage.save(snapshotFor(player, "vitals", "v3"), 5, SaveOptions.of("API", "test"));
            assertTrue(losing instanceof SaveResult.StaleToken, "an equal token must be rejected (strictly newer wins)");
            assertEquals("v2", markerOf(storage.load(player).orElseThrow()));
        }
    }

    // --- history & rollback -----------------------------------------------------

    @Test
    void historyPruningKeepsTheNewestNRows() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage(
                new StorageConfig(3, Duration.ofSeconds(90), false), SERIALIZATION)) {
            for (int i = 0; i < 5; i++) {
                long token = storage.acquireSession(player, "survival-1").token();
                assertTrue(storage.save(snapshotFor(player, "vitals", "save-" + token), token, SaveOptions.of("AUTOSAVE", "survival-1")).saved());
            }
            List<SnapshotSummary> history = storage.listHistory(player, 10);
            assertEquals(3, history.size(), "only the last 3 rows must be kept");
            assertTrue(history.get(0).snapshotId() > history.get(1).snapshotId()
                    && history.get(1).snapshotId() > history.get(2).snapshotId(), "newest first");
            assertTrue(history.get(0).fencingToken() > history.get(1).fencingToken()
                    && history.get(1).fencingToken() > history.get(2).fencingToken(), "keeps the newest by token");
        }
    }

    @Test
    void rollbackReadMaterializesTheOldSnapshotAndResaveCreatesNewRow() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord first = storage.acquireSession(player, "survival-1");
            assertTrue(storage.save(snapshotFor(player, "vitals", "original"), first.token(), SaveOptions.of("TRANSFER", "survival-1")).saved());
            SessionRecord second = storage.acquireSession(player, "survival-1");
            assertTrue(storage.save(snapshotFor(player, "vitals", "current"), second.token(), SaveOptions.of("AUTOSAVE", "survival-1")).saved());

            List<SnapshotSummary> history = storage.listHistory(player, 10);
            assertEquals(2, history.size());
            SnapshotSummary originalRow = history.stream()
                    .filter(row -> row.reason().equals("TRANSFER"))
                    .findFirst()
                    .orElseThrow();

            Optional<PlayerSnapshot> restored = storage.loadSnapshot(originalRow.snapshotId());
            assertTrue(restored.isPresent());
            assertEquals("original", markerOf(restored.get()));
            assertEquals(first.token(), restored.get().fencingToken(), "rollback-read carries the historical token");

            // rollback = re-save the old envelope under a fresh token; nothing is deleted
            SessionRecord fresh = storage.acquireSession(player, "survival-1");
            PlayerSnapshot replay = new PlayerSnapshot(restored.get().envelope(), fresh.token(), Instant.now());
            SaveResult result = storage.save(replay, fresh.token(), SaveOptions.of("ROLLBACK", "survival-1"));
            assertTrue(result.saved());
            assertEquals("original", markerOf(storage.load(player).orElseThrow()));
            assertEquals(3, storage.listHistory(player, 10).size(), "rollback appends, never deletes");
        }
    }

    @Test
    void loadSnapshotWithUnknownIdIsEmpty() {
        try (MongoPlayerStorage storage = openStorage()) {
            assertTrue(storage.loadSnapshot(999_999_999L).isEmpty());
            assertTrue(storage.listHistory(UUID.randomUUID(), 10).isEmpty());
        }
    }

    @Test
    void rollbackReadHistorySummariesExcludePayloads() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            storage.save(snapshotFor(player, "vitals", "a"), session.token(), SaveOptions.of("AUTOSAVE", "survival-1"));
            List<SnapshotSummary> history = storage.listHistory(player, 10);
            assertEquals(1, history.size());
            assertEquals(player, history.get(0).playerId());
            assertEquals(List.of("vitals"), history.get(0).modules());
        }
    }

    // --- transaction atomicity ---------------------------------------------------

    @Test
    void poisonedSnapshotWriteRollsBackSnapshotHistoryAndOutbox() {
        UUID player = UUID.randomUUID();
        // a validator that rejects every sane snapshot: the insert must fail inside the
        // transaction, not before it, and nothing may survive the rollback
        Document validator = new Document("$jsonSchema", new Document("bsonType", "object")
                .append("required", List.of("schemaVersion"))
                .append("properties", new Document("schemaVersion",
                        new Document("bsonType", "int").append("minimum", 1000))));
        try (MongoClient raw = rawClient()) {
            MongoDatabase db = raw.getDatabase(database());
            try (MongoPlayerStorage storage = openStorage()) {
                drainOutbox(storage);
            }
            db.runCommand(new Document("collMod", MongoCollections.PLAYER_SNAPSHOTS)
                    .append("validator", validator)
                    .append("validationLevel", "strict"));
            try {
                try (MongoPlayerStorage storage = openStorage()) {
                    SessionRecord session = storage.acquireSession(player, "survival-1");
                    SaveOptions options = new SaveOptions("AUTOSAVE", "survival-1",
                            List.of(new SaveOptions.OutboxDraft("poison.probe", "{\"probe\":1}")));
                    assertThrows(StorageException.class,
                            () -> storage.save(snapshotFor(player, "vitals", "poisoned"), session.token(), options));
                    assertTrue(storage.load(player).isEmpty(), "the aborted save must not store a snapshot");
                    assertTrue(storage.listHistory(player, 10).isEmpty(), "the aborted save must not write history");
                    assertTrue(storage.pollOutbox(1000).stream().noneMatch(e -> e.playerId().equals(player)),
                            "the aborted save must not leak outbox events");
                }
            } finally {
                db.runCommand(new Document("collMod", MongoCollections.PLAYER_SNAPSHOTS)
                        .append("validator", new Document())
                        .append("validationLevel", "off"));
            }
            // sanity check: after the validator is gone, the same write succeeds
            try (MongoPlayerStorage storage = openStorage()) {
                SessionRecord session = storage.acquireSession(player, "survival-1");
                assertTrue(storage.save(snapshotFor(player, "vitals", "clean"), session.token(), SaveOptions.of("AUTOSAVE", "survival-1")).saved());
            }
        }
    }

    @Test
    void staleWriteLeavesHistoryAndOutboxUntouched() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            assertTrue(storage.save(snapshotFor(player, "vitals", "keep"), session.token(), SaveOptions.of("AUTOSAVE", "survival-1")).saved());
            assertEquals(1, storage.listHistory(player, 10).size());

            drainOutbox(storage);
            SaveOptions withEvent = new SaveOptions("AUTOSAVE", "survival-1",
                    List.of(new SaveOptions.OutboxDraft("stale.probe", "{}")));
            SaveResult stale = storage.save(snapshotFor(player, "vitals", "stale"), session.token() - 1, withEvent);
            assertTrue(stale instanceof SaveResult.StaleToken);
            assertEquals(1, storage.listHistory(player, 10).size(), "a stale write must not write history");
            assertTrue(storage.pollOutbox(1000).stream().noneMatch(e -> e.playerId().equals(player)),
                    "a stale write must not write outbox events");
        }
    }

    // --- statistics ---------------------------------------------------------------

    @Test
    void statsDeltaConcurrencyLosesNothing() throws Exception {
        UUID player = UUID.randomUUID();
        int threads = 8;
        int incrementsPerThread = 25;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = Thread.ofPlatform().name("stats-" + i).start(() -> {
                try (MongoPlayerStorage storage = openStorage()) {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    for (int j = 0; j < incrementsPerThread; j++) {
                        storage.statistics().applyDelta(player, "games_played", 1);
                    }
                } catch (Exception e) {
                    throw new AssertionError("delta worker failed", e);
                }
            });
            workers.add(worker);
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        try (MongoPlayerStorage storage = openStorage()) {
            assertEquals((long) threads * incrementsPerThread, storage.readStats(player).stat("games_played"));
        }
    }

    @Test
    void statsDeltasMixedSignsAndBatch() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            storage.statistics().applyDelta(player, "playtime", 100);
            assertEquals(70, storage.statistics().applyDelta(player, "playtime", -30));
            Map<String, Long> applied = storage.statistics().applyDeltas(player, Map.of("playtime", 5L, "deaths", 1L, "playtime2", 7L));
            assertEquals(75L, applied.get("playtime"));
            assertEquals(1L, applied.get("deaths"));
            PlayerStats stats = storage.readStats(player);
            assertEquals(75L, stats.stat("playtime"));
            assertEquals(1L, stats.stat("deaths"));
            assertEquals(7L, stats.stat("playtime2"));
        }
    }

    @Test
    void statsBaselineRewriteErasesAbsentKeys() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            storage.writeStatsBaseline(player, Map.of("a", 1L, "b", 2L));
            assertEquals(Map.of("a", 1L, "b", 2L), storage.readStats(player).values());
            storage.writeStatsBaseline(player, Map.of("a", 9L));
            assertEquals(Map.of("a", 9L), storage.readStats(player).values(), "absent keys must be erased");
        }
    }

    // --- outbox -------------------------------------------------------------------

    private void drainOutbox(MongoPlayerStorage storage) {
        // anything left over from other tests must not leak into our assertions
        storage.publishOutbox(1000, Integer.MAX_VALUE, entry -> true);
    }

    @Test
    void outboxAppendPollAndMarkPublishedRoundtrip() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            OutboxEntry entry = storage.appendOutbox("test.roundtrip", player, "{\"hello\":\"world\"}");
            assertTrue(entry.id() >= 1);
            List<OutboxEntry> unpublished = storage.pollOutbox(100).stream()
                    .filter(candidate -> candidate.playerId().equals(player))
                    .toList();
            assertEquals(1, unpublished.size());
            assertEquals("test.roundtrip", unpublished.get(0).msgType());

            int published = storage.publishOutbox(100, 3, candidate -> candidate.playerId().equals(player));
            assertEquals(1, published);
            assertTrue(storage.pollOutbox(100).stream().noneMatch(candidate -> candidate.playerId().equals(player)));

            // marking an already-published entry again is a no-op, not an error
            storage.markOutboxPublished(List.of(entry.id()));
            assertTrue(storage.pollOutbox(100).stream().noneMatch(candidate -> candidate.playerId().equals(player)));
        }
    }

    @Test
    void outboxEventsRideTheSnapshotTransaction() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            SessionRecord session = storage.acquireSession(player, "survival-1");
            SaveOptions options = new SaveOptions("TRANSFER", "survival-1", List.of(
                    new SaveOptions.OutboxDraft("transfer.requested", "{\"seq\":1}"),
                    new SaveOptions.OutboxDraft("transfer.presaved", "{\"seq\":2}")));
            assertTrue(storage.save(snapshotFor(player, "vitals", "with-outbox"), session.token(), options).saved());
            List<OutboxEntry> events = storage.pollOutbox(100).stream()
                    .filter(candidate -> candidate.playerId().equals(player))
                    .toList();
            assertEquals(List.of("transfer.requested", "transfer.presaved"),
                    events.stream().map(OutboxEntry::msgType).toList());
            drainOutbox(storage);
        }
    }

    @Test
    void publishOutboxSkipsEntriesAtMaxAttemptsAndRecordsFailures() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            OutboxEntry entry = storage.appendOutbox("test.failing", player, "{}");
            int published = storage.publishOutbox(100, 1, candidate -> false);
            assertEquals(0, published, "failing publisher marks entries failed, never publishes");
            List<OutboxEntry> unpublished = storage.pollOutbox(100).stream()
                    .filter(candidate -> candidate.playerId().equals(player))
                    .toList();
            assertEquals(1, unpublished.size());
            assertEquals(1, unpublished.get(0).attempts());
            assertNotNull(unpublished.get(0).lastError());

            // attempts reached maxAttempts: the entry must not be claimed anymore
            int secondRun = storage.publishOutbox(100, 1, candidate -> true);
            assertEquals(0, secondRun);

            storage.markOutboxFailed(entry.id(), "manual review");
            storage.markOutboxPublished(List.of(entry.id()));
            assertTrue(storage.pollOutbox(100).stream().noneMatch(candidate -> candidate.playerId().equals(player)));
        }
    }

    @Test
    void outboxClaimsInCommitOrder() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.order.a", player, "{}");
            storage.appendOutbox("test.order.b", player, "{}");
            storage.appendOutbox("test.order.c", player, "{}");
            List<String> seen = new ArrayList<>();
            int published = storage.publishOutbox(100, 3, entry -> {
                if (entry.playerId().equals(player)) {
                    seen.add(entry.msgType());
                }
                return true;
            });
            assertTrue(published >= 3);
            assertEquals(List.of("test.order.a", "test.order.b", "test.order.c"), seen.subList(0, 3));
        }
    }

    @Test
    void freshlyClaimedEntryIsNotReclaimedAndStaleClaimIs() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            OutboxEntry entry = storage.appendOutbox("test.claim", player, "{}");

            // a fresh claim (as left behind by a crashed publisher) hides the entry
            try (MongoClient raw = rawClient()) {
                raw.getDatabase(database()).getCollection(MongoCollections.OUTBOX)
                        .updateOne(eq("_id", entry.id()), new Document("$set", new Document("claimedAt", new java.util.Date())));
            }
            assertEquals(0, storage.publishOutbox(100, 3, candidate -> true),
                    "a fresh claim must hide the entry from other publishers");

            // once the claim goes stale, the entry is claimable again
            try (MongoClient raw = rawClient()) {
                raw.getDatabase(database()).getCollection(MongoCollections.OUTBOX)
                        .updateOne(eq("_id", entry.id()),
                                new Document("$set", new Document("claimedAt", java.util.Date.from(Instant.now().minusSeconds(60)))));
            }
            assertEquals(1, storage.publishOutbox(100, 3, candidate -> true),
                    "a stale claim must let the entry be claimed again");
        }
    }

    @Test
    void concurrentPublishersDoNotDoubleClaim() throws Exception {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
        }
        try (MongoPlayerStorage setup = openStorage()) {
            for (int i = 0; i < 6; i++) {
                setup.appendOutbox("test.race." + i, player, "{}");
            }
        }
        ConcurrentLinkedQueue<Long> publishedIds = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread worker = Thread.ofPlatform().name("outbox-publisher-" + i).start(() -> {
                try (MongoPlayerStorage storage = openStorage()) {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    storage.publishOutbox(100, 3, entry -> {
                        if (entry.playerId().equals(player)) {
                            publishedIds.add(entry.id());
                        }
                        try {
                            Thread.sleep(25); // hold the claim a beat to widen the overlap window
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    });
                } catch (Exception e) {
                    throw new AssertionError("concurrent publisher failed", e);
                }
            });
            workers.add(worker);
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        List<Long> claimed = new ArrayList<>(publishedIds);
        assertEquals(claimed.size(), claimed.stream().distinct().count(), "no entry may be claimed twice");
        assertEquals(6, claimed.size(), "both publishers together must publish all six entries");
    }

    // --- sessions ------------------------------------------------------------------

    @Test
    void sessionAcquireTransitionHeartbeatAndRelease() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            assertEquals(SessionState.CONNECTING, session.state());
            assertTrue(session.token() > 0);
            assertTrue(session.owned());
            assertTrue(session.leaseExpiresAt().isAfter(session.startedAt()));

            Optional<SessionRecord> transitioned = storage.transitionSession(player, session.token(), SessionState.ACTIVE);
            assertTrue(transitioned.isPresent());
            assertEquals(SessionState.ACTIVE, transitioned.get().state());

            Optional<SessionRecord> heartbeat = storage.heartbeatSession(player, session.token());
            assertTrue(heartbeat.isPresent());
            assertFalse(heartbeat.get().leaseExpiresAt().isBefore(transitioned.get().leaseExpiresAt()));

            assertTrue(storage.releaseSession(player, session.token()));
            assertFalse(storage.releaseSession(player, session.token()), "second release is a no-op");
            Optional<SessionRecord> released = storage.getSession(player);
            assertTrue(released.isPresent());
            assertEquals(SessionState.RELEASED, released.get().state());
        }
    }

    @Test
    void supersededSessionTokenLosesEveryGuard() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord first = storage.acquireSession(player, "survival-1");
            SessionRecord second = storage.acquireSession(player, "survival-2");
            assertTrue(second.token() > first.token());

            assertTrue(storage.transitionSession(player, first.token(), SessionState.ACTIVE).isEmpty());
            assertTrue(storage.heartbeatSession(player, first.token()).isEmpty());
            assertFalse(storage.releaseSession(player, first.token()));

            try (MongoClient raw = rawClient()) {
                String oldState = raw.getDatabase(database()).getCollection(MongoCollections.PLAYER_SESSIONS)
                        .find(eq("token", first.token()))
                        .first()
                        .getString("state");
                assertEquals(SessionState.RELEASED.name(), oldState, "the superseded session must be marked released");
            }
        }
    }

    @Test
    void transitionToConnectingOrReleasedIsRefused() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage()) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            assertThrows(IllegalArgumentException.class, () -> storage.transitionSession(player, session.token(), SessionState.CONNECTING));
            assertThrows(IllegalArgumentException.class, () -> storage.transitionSession(player, session.token(), SessionState.RELEASED));
        }
    }

    @Test
    void getSessionForUnknownPlayerIsEmpty() {
        try (MongoPlayerStorage storage = openStorage()) {
            assertTrue(storage.getSession(UUID.randomUUID()).isEmpty());
            assertTrue(storage.load(UUID.randomUUID()).isEmpty());
        }
    }

    @Test
    void gzipRoundtripThroughStorage() {
        UUID player = UUID.randomUUID();
        try (MongoPlayerStorage storage = openStorage(STORAGE, new SerializationConfig(true, 4 * 1024 * 1024))) {
            SessionRecord session = storage.acquireSession(player, "survival-1");
            assertTrue(storage.save(snapshotFor(player, "vitals", "zipped"), session.token(), SaveOptions.of("AUTOSAVE", "survival-1")).saved());
            assertEquals("zipped", markerOf(storage.load(player).orElseThrow()));
        }
    }
}
