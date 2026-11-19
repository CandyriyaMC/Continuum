package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.storage.api.SchemaMigrationException;

import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import org.bson.conversions.Bson;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.eq;

/**
 * The MongoDB counterpart of the SQL migration runner, and it is deliberately
 * NOT a migration table. MongoDB is schemaless: there is no DDL to apply and
 * no {@code schema_migrations} bookkeeping. What a Mongo deployment still
 * needs before it serves traffic is the operational equivalent of a migrated
 * schema:
 *
 * <ol>
 *   <li>the collections must exist (created here when missing);</li>
 *   <li>the indexes must exist (created here when missing, then verified via
 *       {@code listIndexes});</li>
 *   <li>the fencing-token counter documents must exist (seeded when missing),
 *       and be bumped to at least the highest token/id present in the data, so
 *       a counter lost to a partial restore cannot re-issue a token that is
 *       already in use;</li>
 *   <li>anything unexpected refuses to boot — a half-prepared deployment is
 *       never served.</li>
 * </ol>
 *
 * <p>The asymmetry vs SQL is real and intentional: SQL replays versioned,
 * checksummed DDL files; Mongo validates the operational envelope (collections,
 * indexes, counters) on every boot. Both fail fast with
 * {@link SchemaMigrationException} and both are idempotent. A Mongo deployment
 * that wants a hard schema can additionally pin a {@code $jsonSchema} validator
 * on the collections — it is not required for correctness (all writes go
 * through the guarded paths here) and is therefore out of scope of this
 * bootstrap.</p>
 *
 * <p>All steps are idempotent and safe to run on every boot, including on a
 * fresh database.</p>
 */
final class MongoSchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(MongoSchemaMigrations.class);

    /** Every collection this storage expects, in creation order. */
    static final List<String> COLLECTIONS = List.of(
            MongoCollections.PLAYERS,
            MongoCollections.PLAYER_SNAPSHOTS,
            MongoCollections.PLAYER_SNAPSHOT_HISTORY,
            MongoCollections.PLAYER_SESSIONS,
            MongoCollections.PLAYER_STATS,
            MongoCollections.OUTBOX,
            MongoCollections.SNAPSHOT_COUNTERS);

    /** The unique (or lookup) indexes expected per collection, keyed by index name. */
    static final Map<String, ExpectedIndex> EXPECTED_INDEXES = expectedIndexes();

    private MongoSchemaMigrations() {
    }

    /**
     * Prepares the database for serving: collections, indexes, counters, validation.
     *
     * @throws SchemaMigrationException when any step fails; the storage must not serve
     */
    static void ensure(MongoDatabase database) {
        try {
            ensureCollections(database);
            ensureIndexes(database);
            seedCounters(database);
            bumpCountersToDataMaxima(database);
            validate(database);
        } catch (SchemaMigrationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SchemaMigrationException("failed to prepare the MongoDB schema: " + e.getMessage(), e);
        }
    }

    private static void ensureCollections(MongoDatabase database) {
        Set<String> existing = database.listCollectionNames().into(new java.util.HashSet<>());
        for (String name : COLLECTIONS) {
            if (existing.contains(name)) {
                continue;
            }
            try {
                database.createCollection(name);
                log.info("created collection {}", name);
            } catch (MongoCommandException e) {
                // 48 = NamespaceExists: a concurrent boot created it first; that is fine
                if (e.getErrorCode() != 48) {
                    throw new SchemaMigrationException("failed to create collection " + name + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private static void ensureIndexes(MongoDatabase database) {
        for (Map.Entry<String, ExpectedIndex> entry : EXPECTED_INDEXES.entrySet()) {
            ExpectedIndex expected = entry.getValue();
            MongoCollection<Document> collection = database.getCollection(expected.collection());
            collection.createIndex(expected.keys(), new IndexOptions().name(entry.getKey()).unique(expected.unique()));
        }
        // createIndex is idempotent, but "asked for it" is not "got it": read the real thing back
        for (ExpectedIndex expected : EXPECTED_INDEXES.values()) {
            MongoCollection<Document> collection = database.getCollection(expected.collection());
            Set<String> actual = indexNames(collection);
            if (!actual.contains(expected.name())) {
                throw new SchemaMigrationException(
                        "expected index " + expected.name() + " on " + expected.collection() + " is missing after creation; found: " + actual);
            }
        }
    }

    private static Set<String> indexNames(MongoCollection<Document> collection) {
        return collection.listIndexes()
                .into(new ArrayList<>())
                .stream()
                .map(doc -> doc.getString("name"))
                .collect(Collectors.toSet());
    }

    private static void seedCounters(MongoDatabase database) {
        MongoCollection<Document> counters = database.getCollection(MongoCollections.SNAPSHOT_COUNTERS);
        for (String key : MongoCollections.COUNTER_KEYS) {
            counters.updateOne(eq("_id", key),
                    Updates.combine(Updates.setOnInsert("value", 0L)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        }
    }

    /**
     * Raises each counter to at least the highest id/token present in the data.
     * Guards against a counter document lost to a partial restore or a manual
     * "repair": without this, the next write could re-issue a fencing token
     * that is already in use and fence-check the wrong write.
     */
    private static void bumpCountersToDataMaxima(MongoDatabase database) {
        long fenceMax = maxOf(database.getCollection(MongoCollections.PLAYER_SESSIONS), "token",
                maxOf(database.getCollection(MongoCollections.PLAYER_SNAPSHOTS), "fencingToken",
                        maxOf(database.getCollection(MongoCollections.PLAYER_SNAPSHOT_HISTORY), "fencingToken", 0L)));
        raiseCounter(database, MongoCollections.COUNTER_FENCE, fenceMax);
        raiseCounter(database, MongoCollections.COUNTER_HISTORY, maxOf(database.getCollection(MongoCollections.PLAYER_SNAPSHOT_HISTORY), "_id", 0L));
        raiseCounter(database, MongoCollections.COUNTER_SESSION, maxOf(database.getCollection(MongoCollections.PLAYER_SESSIONS), "_id", 0L));
        raiseCounter(database, MongoCollections.COUNTER_OUTBOX, maxOf(database.getCollection(MongoCollections.OUTBOX), "_id", 0L));
    }

    private static long maxOf(MongoCollection<Document> collection, String field, long fallback) {
        List<Document> result = collection.aggregate(List.of(
                new Document("$group", new Document("_id", null).append("m", new Document("$max", "$" + field)))))
                .into(new ArrayList<>());
        if (result.isEmpty()) {
            return fallback;
        }
        Number max = (Number) result.get(0).get("m");
        return max == null ? fallback : Math.max(fallback, max.longValue());
    }

    private static void raiseCounter(MongoDatabase database, String key, long atLeast) {
        if (atLeast <= 0) {
            return;
        }
        // $max keeps the counter at max(current, atLeast); never lowers it
        database.getCollection(MongoCollections.SNAPSHOT_COUNTERS)
                .updateOne(eq("_id", key), new Document("$max", new Document("value", atLeast)));
    }

    private static void validate(MongoDatabase database) {
        MongoCollection<Document> counters = database.getCollection(MongoCollections.SNAPSHOT_COUNTERS);
        for (String key : MongoCollections.COUNTER_KEYS) {
            Document doc = counters.find(eq("_id", key)).first();
            if (doc == null) {
                throw new SchemaMigrationException("fencing counter document '" + key + "' is missing after bootstrap");
            }
            Object value = doc.get("value");
            if (!(value instanceof Number)) {
                throw new SchemaMigrationException("fencing counter document '" + key + "' has a non-numeric value");
            }
            if (((Number) value).longValue() < 0) {
                throw new SchemaMigrationException("fencing counter document '" + key + "' has a negative value");
            }
        }
    }

    private static Map<String, ExpectedIndex> expectedIndexes() {
        Map<String, ExpectedIndex> indexes = new LinkedHashMap<>();
        add(indexes, "uk_sessions_token", MongoCollections.PLAYER_SESSIONS, Indexes.ascending("token"), true);
        add(indexes, "idx_sessions_player_token", MongoCollections.PLAYER_SESSIONS,
                Indexes.compoundIndex(Indexes.ascending("playerUuid"), Indexes.descending("token")), false);
        add(indexes, "idx_sessions_lease", MongoCollections.PLAYER_SESSIONS, Indexes.ascending("leaseExpiresAt"), false);
        add(indexes, "uk_player_stats_player_stat", MongoCollections.PLAYER_STATS,
                Indexes.ascending("playerUuid", "statKey"), true);
        add(indexes, "idx_player_snapshot_history_player_token", MongoCollections.PLAYER_SNAPSHOT_HISTORY,
                Indexes.compoundIndex(Indexes.ascending("playerUuid"), Indexes.descending("fencingToken")), false);
        add(indexes, "idx_outbox_poll", MongoCollections.OUTBOX,
                Indexes.ascending("publishedAt", "_id"), false);
        return Map.copyOf(indexes);
    }

    private static void add(Map<String, ExpectedIndex> indexes, String name, String collection, Bson keys, boolean unique) {
        // Indexes.ascending/orderBy produce ordered Bson documents; keep them as-is
        indexes.put(name, new ExpectedIndex(name, collection, keys, unique));
    }

    /** One index the storage expects to find at boot. */
    record ExpectedIndex(String name, String collection, Bson keys, boolean unique) {
    }
}
