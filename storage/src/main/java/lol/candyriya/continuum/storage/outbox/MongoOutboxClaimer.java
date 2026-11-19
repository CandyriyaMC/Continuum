package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.messaging.outbox.OutboxClaimer;
import lol.candyriya.continuum.messaging.outbox.OutboxEvent;

import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;

/**
 * MongoDB implementation of the relay claim contract, backed by the same
 * {@code outbox} collection the storage layer writes.
 *
 * <p>MongoDB has no row locks to hold across the publish window, so this
 * claimer uses the marker protocol instead of the SQL side's held
 * transaction:</p>
 * <ol>
 *   <li>{@link #claimBatch(int)} claims in one transaction: repeated
 *       {@code findOneAndUpdate} of the oldest entry with
 *       {@code publishedAt == null AND attempts < maxAttempts AND
 *       (claimedAt == null OR claimedAt < now - 30s)}, setting
 *       {@code claimedAt = now}. A competing claim in another transaction
 *       hits a write conflict and the driver's retry pass observes the
 *       claimed entry, so double-claim is not possible;</li>
 *   <li>entries claimed by a crashed relay become claimable again once
 *       their {@code claimedAt} is older than 30s — a crash costs at most a
 *       30s delay and consumers see a duplicate (they dedupe on the
 *       envelope's {@code eventId}; the outbox id is the natural
 *       claim marker);</li>
 *   <li>the relay publishes outside any transaction; the outcome (published
 *       or attempts + 1 with the truncated error) is recorded by
 *       {@link #markPublished(Collection)} / {@link #markFailed(Collection, String)}
 *       in a second transaction, clearing {@code claimedAt};</li>
 *   <li>if the outcome transaction fails after the broker confirmed, the
 *       entries are reclaimed and republished — at-least-once with
 *       idempotent consumers.</li>
 * </ol>
 *
 * <p>Same wiring rule as the SQL twin: exactly one drain per outbox — point
 * the relay at the table or run the storage-side {@code publishOutbox}
 * sweep, never both. Wire the relay via
 * {@code OutboxRelay(claimer, transport, codec, config)}; one claimer per
 * relay, one relay per outbox.</p>
 */
public final class MongoOutboxClaimer implements OutboxClaimer, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoOutboxClaimer.class);

    /** Mirrors the stale bound used by the storage-side publish path. */
    static final Duration CLAIM_STALE_AFTER = Duration.ofSeconds(OutboxClaimSupport.CLAIM_STALE_SECONDS);

    /** Index the claim filter reads; the bootstrap must keep it partial on unpublished rows. */
    static final String OUTBOX_COLLECTION = "outbox";

    private static final TransactionOptions TX = TransactionOptions.builder()
            .readConcern(ReadConcern.MAJORITY)
            .writeConcern(WriteConcern.MAJORITY)
            .build();

    private final MongoCollection<Document> outbox;
    private final int maxAttempts;
    private final MongoClient client;

    // every claim adds its eventId -> rowId mapping here; marks remove entries.
    // claiming twice before marking must not lose the first batch's mapping
    private final Map<UUID, Long> claimMap = new ConcurrentHashMap<>();

    public MongoOutboxClaimer(MongoClient client, MongoDatabase database) {
        this(client, database, OutboxClaimSupport.DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * @param client      the client owning {@code database}
     * @param database    the database holding the {@code outbox} collection
     * @param maxAttempts rows with {@code attempts} at or above this are never claimed
     */
    public MongoOutboxClaimer(MongoClient client, MongoDatabase database, int maxAttempts) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (database == null) {
            throw new IllegalArgumentException("database must not be null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.outbox = database.getCollection(OUTBOX_COLLECTION);
        this.client = client;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public List<OutboxEvent> claimBatch(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        Date staleBefore = Date.from(Instant.now().minus(CLAIM_STALE_AFTER));
        Date now = new Date();
        try (ClientSession session = client.startSession()) {
            List<Document> claimed = session.withTransaction(() -> {
                List<Document> out = new ArrayList<>(limit);
                for (int i = 0; i < limit; i++) {
                    Document doc = outbox.findOneAndUpdate(session,
                            and(eq("publishedAt", null),
                                    lt("attempts", maxAttempts),
                                    or(eq("claimedAt", null), lt("claimedAt", staleBefore))),
                            Updates.set("claimedAt", now),
                            new FindOneAndUpdateOptions()
                                    .sort(Sorts.ascending("_id"))
                                    .returnDocument(ReturnDocument.AFTER));
                    if (doc == null) {
                        break;
                    }
                    out.add(doc);
                }
                return out;
            }, TX);
            Map<UUID, Long> eventToRow = new ConcurrentHashMap<>();
            List<OutboxEvent> events = new ArrayList<>(claimed.size());
            for (Document doc : claimed) {
                long rowId = ((Number) doc.get("_id")).longValue();
                String playerUuid = doc.getString("playerUuid");
                int attempts = doc.get("attempts") instanceof Number n ? n.intValue() : 0;
                Date created = doc.getDate("createdAt");
                UUID eventId = OutboxClaimSupport.eventIdFor(rowId);
                eventToRow.put(eventId, rowId);
                events.add(OutboxClaimSupport.toEvent(rowId, doc.getString("msgType"), playerUuid,
                        doc.getString("payloadJson"), attempts, created.toInstant()));
            }
            claimMap.putAll(eventToRow);
            return List.copyOf(events);
        } catch (RuntimeException e) {
            throw StorageException.unavailable("failed to claim outbox batch", e);
        }
    }

    @Override
    public void markPublished(Collection<UUID> eventIds) {
        applyOutcome(eventIds, true, null);
    }

    @Override
    public void markFailed(Collection<UUID> eventIds, String reason) {
        applyOutcome(eventIds, false, OutboxClaimSupport.truncate(reason));
    }

    private void applyOutcome(Collection<UUID> eventIds, boolean published, String error) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        Map<UUID, Long> mapping = claimMap;
        Set<Long> rowIds = new HashSet<>();
        for (UUID eventId : new HashSet<>(eventIds)) {
            Long rowId = mapping.get(eventId);
            if (rowId == null) {
                log.warn("outbox outcome references unknown event {} - ignoring it (claimed after the last batch?)", eventId);
                continue;
            }
            rowIds.add(rowId);
        }
        if (rowIds.isEmpty()) {
            return;
        }
        Date stamp = new Date();
        try (ClientSession session = client.startSession()) {
            session.withTransaction(() -> {
                if (published) {
                    outbox.updateMany(session,
                            and(in("_id", rowIds), eq("publishedAt", null)),
                            Updates.combine(Updates.set("publishedAt", stamp), Updates.set("claimedAt", null)));
                } else {
                    for (Long rowId : rowIds) {
                        outbox.updateOne(session,
                                and(eq("_id", rowId), eq("publishedAt", null)),
                                Updates.combine(
                                        Updates.inc("attempts", 1),
                                        Updates.set("lastError", error),
                                        Updates.set("claimedAt", null)));
                    }
                }
                return null;
            }, TX);
        } catch (RuntimeException e) {
            throw StorageException.unavailable("failed to record outbox outcomes", e);
        }
        // the batch is resolved; drop its mappings so the map stays proportional to open claims
        for (UUID eventId : new HashSet<>(eventIds)) {
            claimMap.remove(eventId);
        }
    }

    /** No connections are held between claim and outcome; closing just drops the mapping. */
    @Override
    public void close() {
        claimMap.clear();
    }
}
