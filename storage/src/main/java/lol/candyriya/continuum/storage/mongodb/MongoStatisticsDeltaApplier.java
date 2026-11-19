package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.storage.api.StatisticsDeltaApplier;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;

import org.bson.Document;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * {@code $inc} accumulate-mutations backed by the same client as the rest of storage.
 * Never read-modify-write from application memory: the atomic {@code $inc} serializes
 * concurrent increments on the server, so parallel backends cannot lose updates, no
 * fencing token is required for this path.
 *
 * <p>Each call runs in its own transaction: the {@code $inc} and the read of the new
 * value happen inside that transaction, so the returned value is exactly what this
 * call stored. The upsert inserts the document on first touch — {@code $inc} on an
 * absent field starts from 0, so the first delta behaves exactly like every later one.</p>
 */
final class MongoStatisticsDeltaApplier implements StatisticsDeltaApplier {

    private static final FindOneAndUpdateOptions UPSERT_AFTER = new FindOneAndUpdateOptions()
            .upsert(true)
            .returnDocument(ReturnDocument.AFTER);

    private final MongoPlayerStorage storage;

    MongoStatisticsDeltaApplier(MongoPlayerStorage storage) {
        this.storage = storage;
    }

    @Override
    public long applyDelta(UUID playerId, String statKey, long delta) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        String key = MongoPlayerStorage.validateStatKey(statKey);
        storage.ensureOpen();
        String uuid = playerId.toString();
        try {
            return storage.inTransaction(session -> applyDeltaInTransaction(session, uuid, key, delta));
        } catch (MongoPlayerStorage.DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to apply delta of " + delta + " to stat " + key + " for " + playerId, e.getCause());
        }
    }

    @Override
    public Map<String, Long> applyDeltas(UUID playerId, Map<String, Long> deltas) throws StorageException {
        Objects.requireNonNull(playerId, "playerId must not be null");
        Map<String, Long> copy = deltas == null ? Map.of() : new LinkedHashMap<>(deltas);
        for (String key : copy.keySet()) {
            MongoPlayerStorage.validateStatKey(key);
        }
        storage.ensureOpen();
        String uuid = playerId.toString();
        try {
            Map<String, Long> updated = new LinkedHashMap<>();
            storage.inTransaction(session -> {
                for (Map.Entry<String, Long> entry : copy.entrySet()) {
                    updated.put(entry.getKey(), applyDeltaInTransaction(session, uuid, entry.getKey(), entry.getValue()));
                }
                return null;
            });
            return Collections.unmodifiableMap(updated);
        } catch (MongoPlayerStorage.DuplicateKeySignal e) {
            throw StorageException.unavailable("failed to apply stat deltas for " + playerId, e.getCause());
        }
    }

    private long applyDeltaInTransaction(ClientSession session, String uuid, String statKey, long delta) {
        MongoCollection<Document> stats = storage.statsCollection();
        Date now = new Date();
        Document updated = stats.findOneAndUpdate(session,
                and(eq("playerUuid", uuid), eq("statKey", statKey)),
                Updates.combine(Updates.inc("statValue", delta), Updates.set("updatedAt", now)),
                UPSERT_AFTER);
        if (updated == null) {
            // upsert returns the stored document; null here means the server is lying, not that anything raced
            throw new IllegalStateException("stat row for " + statKey + " vanished inside the delta transaction");
        }
        Object raw = updated.get("statValue");
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException("stat row for " + statKey + " has a non-numeric statValue");
        }
        return number.longValue();
    }
}
