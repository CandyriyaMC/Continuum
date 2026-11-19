package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.ne;

/**
 * MongoDB scan for orphaned sessions against the same {@code player_sessions}
 * collection the storage layer writes. Read-only; marking goes through the
 * storage API (fencing-guarded), never through this class.
 */
public final class MongoOrphanedSessionFinder implements OrphanedSessionFinder {

    private static final Logger log = LoggerFactory.getLogger(MongoOrphanedSessionFinder.class);

    /** Same collection the storage layer bootstraps ({@code player_sessions}). */
    static final String PLAYER_SESSIONS_COLLECTION = "player_sessions";

    private final MongoCollection<Document> sessions;

    public MongoOrphanedSessionFinder(MongoDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("database must not be null");
        }
        this.sessions = database.getCollection(PLAYER_SESSIONS_COLLECTION);
    }

    @Override
    public List<OrphanedSession> findOrphans(Instant olderThan, int limit) throws StorageException {
        if (olderThan == null) {
            throw new IllegalArgumentException("olderThan must not be null");
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got " + limit);
        }
        Bson filter = and(
                ne("state", SessionState.RELEASED.name()),
                ne("state", SessionState.RECOVERY_REQUIRED.name()),
                eq("endedAt", null),
                lt("leaseExpiresAt", Date.from(olderThan)));
        List<OrphanedSession> orphans = new ArrayList<>();
        try {
            for (Document doc : sessions.find(filter).sort(Sorts.ascending("leaseExpiresAt")).limit(limit)) {
                String state = doc.getString("state");
                SessionState parsed;
                try {
                    parsed = SessionState.valueOf(state);
                } catch (IllegalArgumentException e) {
                    log.warn("player_sessions._id {} has unknown state '{}'; skipping", doc.get("_id"), state);
                    continue;
                }
                orphans.add(new OrphanedSession(
                        ((Number) doc.get("_id")).longValue(),
                        UUID.fromString(doc.getString("playerUuid")),
                        doc.getString("serverId"),
                        ((Number) doc.get("token")).longValue(),
                        parsed,
                        requireDate(doc, "startedAt").toInstant(),
                        requireDate(doc, "lastHeartbeat").toInstant(),
                        requireDate(doc, "leaseExpiresAt").toInstant()));
            }
            return List.copyOf(orphans);
        } catch (RuntimeException e) {
            throw StorageException.unavailable("failed to scan for orphaned sessions", e);
        }
    }

    private static Date requireDate(Document doc, String field) {
        Date value = doc.getDate(field);
        if (value == null) {
            throw new IllegalStateException("player_sessions document " + doc.get("_id") + " is missing " + field);
        }
        return value;
    }
}
