package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.config.MongoPool;
import lol.candyriya.continuum.common.config.MongoSettings;
import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.storage.api.StorageConfig;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.RecoveryRequiredPayload;
import lol.candyriya.continuum.messaging.outbox.OutboxEvent;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.SessionRecord;
import lol.candyriya.continuum.storage.mongodb.MongoPlayerStorage;
import lol.candyriya.continuum.storage.recovery.MongoOrphanedSessionFinder;
import lol.candyriya.continuum.storage.recovery.OrphanedSession;
import lol.candyriya.continuum.storage.recovery.RecoveryManager;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbox claimer and orphaned-session scan against a real MongoDB replica
 * set. Requires docker (or an external replica set via the
 * {@code CONTINUUM_TEST_MONGODB_URI} environment variable); without either,
 * the whole class skips cleanly.
 */
class MongoOutboxClaimerIntegrationTest {

    private static final SerializationConfig SERIALIZATION = new SerializationConfig(false, 4 * 1024 * 1024);
    private static final StorageConfig STORAGE = StorageConfig.defaults();
    private static final MongoPool POOL = new MongoPool(8, 8, Duration.ofSeconds(2));

    private static final String EXTERNAL_URI = System.getProperty("continuum.test.mongodb.uri",
            System.getenv("CONTINUUM_TEST_MONGODB_URI"));

    private static MongoDBContainer container;
    private static MongoClient client;
    private static String databaseName;

    @BeforeAll
    static void startDatabase() {
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            client = MongoClients.create(EXTERNAL_URI);
            databaseName = "continuum-outbox-test";
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MongoDB integration tests");
        container = new MongoDBContainer(DockerImageName.parse("mongo:8.0"))
                .withReplicaSet();
        container.start();
        client = MongoClients.create(container.getReplicaSetUrl());
        databaseName = "continuum-outbox-test";
    }

    @AfterAll
    static void stopDatabase() {
        if (client != null) {
            client.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    private static MongoPlayerStorage openStorage() {
        return MongoPlayerStorage.open(new MongoSettings(uri(), databaseName, POOL), SERIALIZATION, STORAGE);
    }

    private static String uri() {
        if (EXTERNAL_URI != null && !EXTERNAL_URI.isBlank()) {
            return EXTERNAL_URI;
        }
        return container.getReplicaSetUrl();
    }

    private static MongoDatabase database() {
        return client.getDatabase(databaseName);
    }

    private static void drainOutbox(MongoPlayerStorage storage) {
        storage.publishOutbox(1000, Integer.MAX_VALUE, entry -> true);
    }

    // --- claimer -----------------------------------------------------------------

    @Test
    void claimMarksRowsClaimedAndMapsOldestFirst() {
        UUID player = UUID.randomUUID();
        long idA;
        long idB;
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            idA = storage.appendOutbox("test.mongo.claim.a", player, "{\"seq\":1}").id();
            idB = storage.appendOutbox("test.mongo.claim.b", player, "{\"seq\":2}").id();
        }

        MongoOutboxClaimer claimer = new MongoOutboxClaimer(client, database());
        try {
            List<UUID> wanted = List.of(OutboxClaimSupport.eventIdFor(idA), OutboxClaimSupport.eventIdFor(idB));
            List<OutboxEvent> claimed = claimer.claimBatch(10).stream()
                    .filter(event -> wanted.contains(event.eventId())).toList();
            assertEquals(List.of(OutboxClaimSupport.eventIdFor(idA), OutboxClaimSupport.eventIdFor(idB)),
                    claimed.stream().map(OutboxEvent::eventId).toList());
            for (OutboxEvent event : claimed) {
                assertEquals(player.toString(), event.correlationId());
                assertEquals(0, event.attempts());
            }

            // the claimed marker must be visible for the next claim to skip
            List<Document> docs = database().getCollection("outbox").find(
                            Filters.in("_id", List.of(idA, idB)))
                    .into(new ArrayList<>());
            assertEquals(2, docs.size());
            for (Document doc : docs) {
                assertNotNull(doc.getDate("claimedAt"), "the claim must stamp claimedAt");
            }

            // a second claim must not see them
            List<OutboxEvent> again = claimer.claimBatch(10).stream()
                    .filter(event -> wanted.contains(event.eventId())).toList();
            assertTrue(again.isEmpty());

            claimer.markPublished(claimed.stream().map(OutboxEvent::eventId).toList());
            List<Document> after = database().getCollection("outbox").find(
                            Filters.in("_id", List.of(idA, idB)))
                    .into(new ArrayList<>());
            for (Document doc : after) {
                assertNotNull(doc.getDate("publishedAt"), "published rows must carry publishedAt");
                assertEquals(null, doc.getDate("claimedAt"), "published rows must clear claimedAt");
            }
        } finally {
            claimer.close();
        }
    }

    @Test
    void staleClaimsAreReclaimedAfterTheBound() {
        UUID player = UUID.randomUUID();
        long id;
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            id = storage.appendOutbox("test.mongo.stale", player, "{}").id();
        }
        UUID wanted = OutboxClaimSupport.eventIdFor(id);

        MongoOutboxClaimer claimer = new MongoOutboxClaimer(client, database());
        try {
            List<OutboxEvent> first = claimer.claimBatch(10).stream()
                    .filter(event -> event.eventId().equals(wanted)).toList();
            assertEquals(1, first.size());

            // age the claim marker past the staleness bound to simulate a dead relay
            Date past = Date.from(Instant.now().minusSeconds(120));
            database().getCollection("outbox").updateMany(
                    Filters.eq("_id", id),
                    Updates.set("claimedAt", past));

            List<OutboxEvent> reclaimed = claimer.claimBatch(10).stream()
                    .filter(event -> event.eventId().equals(wanted)).toList();
            assertEquals(1, reclaimed.size(), "stale claims must become claimable again");
            assertEquals(first.get(0).eventId(), reclaimed.get(0).eventId(),
                    "the same row must be reclaimed with the same relay handle");

            claimer.markPublished(reclaimed.stream().map(OutboxEvent::eventId).toList());
        } finally {
            claimer.close();
        }
    }

    @Test
    void markFailedBumpsAttemptsTruncatesAndClearsClaim() {
        UUID player = UUID.randomUUID();
        long id;
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            id = storage.appendOutbox("test.mongo.fail", player, "{}").id();
        }
        UUID wanted = OutboxClaimSupport.eventIdFor(id);

        MongoOutboxClaimer claimer = new MongoOutboxClaimer(client, database());
        try {
            OutboxEvent event = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventId().equals(wanted))
                    .findFirst().orElseThrow();
            claimer.markFailed(List.of(event.eventId()), "x".repeat(600));

            Document doc = database().getCollection("outbox").find(Filters.eq("_id", id)).first();
            assertNotNull(doc);
            assertEquals(1L, ((Number) doc.get("attempts")).longValue());
            assertEquals(512, ((String) doc.get("lastError")).length());
            assertEquals(null, doc.getDate("claimedAt"), "a failed mark must free the row for the next claim");

            // the row must be claimable again
            List<OutboxEvent> again = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventId().equals(wanted)).toList();
            assertEquals(1, again.size());
            claimer.markPublished(again.stream().map(OutboxEvent::eventId).toList());
        } finally {
            claimer.close();
        }
    }

    @Test
    void rowsAtMaxAttemptsAreNeverClaimed() {
        UUID player = UUID.randomUUID();
        long id;
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            id = storage.appendOutbox("test.mongo.budget", player, "{}").id();
        }
        UUID wanted = OutboxClaimSupport.eventIdFor(id);

        MongoOutboxClaimer claimer = new MongoOutboxClaimer(client, database(), 1);
        try {
            OutboxEvent event = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventId().equals(wanted))
                    .findFirst().orElseThrow();
            claimer.markFailed(List.of(event.eventId()), "boom");
            List<OutboxEvent> again = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventId().equals(wanted)).toList();
            assertTrue(again.isEmpty());
        } finally {
            claimer.close();
            try (MongoPlayerStorage storage = openStorage()) {
                drainOutbox(storage);
            }
        }
    }

    // --- orphan scan + recovery --------------------------------------------------

    @Test
    void orphanedSessionIsFoundMarkedAndRecoveredIdempotently() {
        UUID player = UUID.randomUUID();
        SessionRecord session;
        try (MongoPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            session = storage.acquireSession(player, "recovery-backend");
            // force the lease into the past to simulate a dead owner
            Date past = Date.from(Instant.now().minus(Duration.ofMinutes(10)));
            database().getCollection("player_sessions").updateOne(
                    Filters.and(Filters.eq("playerUuid", player.toString()), Filters.eq("token", session.token())),
                    Updates.set("leaseExpiresAt", past));
        }

        List<OrphanedSession> orphans = new MongoOrphanedSessionFinder(database())
                .findOrphans(Instant.now(), 100);
        assertTrue(orphans.stream().anyMatch(orphan -> orphan.playerId().equals(player)
                        && orphan.sessionId() == session.sessionId()),
                "the expired session must be found");

        try (MongoPlayerStorage storage = openStorage()) {
            RecoveryManager manager = new RecoveryManager(storage,
                    new MongoOrphanedSessionFinder(database()),
                    new lol.candyriya.continuum.common.config.RecoveryConfig(true, Duration.ofMillis(50), List.of()));
            assertEquals(1, manager.recoverOnce(), "recovery must mark the orphaned session");

            Optional<SessionRecord> after = storage.getSession(player);
            assertTrue(after.isPresent());
            assertEquals(SessionState.RECOVERY_REQUIRED, after.get().state());

            List<OutboxEntry> events = storage.pollOutbox(10).stream()
                    .filter(entry -> entry.playerId().equals(player)).toList();
            assertEquals(1, events.size());
            assertEquals(BackboneEvents.RECOVERY_REQUIRED, events.get(0).msgType());
            EnvelopeCodec codec = new EnvelopeCodec(BackboneEvents.create(), 16 * 1024);
            MessageEnvelope<RecoveryRequiredPayload> envelope = codec.decode(
                    events.get(0).payloadJson().getBytes(StandardCharsets.UTF_8), RecoveryRequiredPayload.class);
            assertEquals(player, envelope.payload().playerId());
            assertEquals(RecoveryManager.sessionUuidFor(session.sessionId()), envelope.payload().sessionId());
            assertEquals("recovery-backend", envelope.payload().backendId());

            assertEquals(0, manager.recoverOnce(), "a second sweep must be a no-op");
            drainOutbox(storage);
        }
    }
}
