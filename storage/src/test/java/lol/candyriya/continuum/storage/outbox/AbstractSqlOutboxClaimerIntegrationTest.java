package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.MysqlPool;
import lol.candyriya.continuum.common.config.MysqlSettings;
import lol.candyriya.continuum.common.config.SerializationConfig;
import lol.candyriya.continuum.common.session.SessionState;
import lol.candyriya.continuum.messaging.outbox.OutboxEvent;
import lol.candyriya.continuum.messaging.outbox.OutboxRelay;
import lol.candyriya.continuum.messaging.outbox.OutboxRelayConfig;
import lol.candyriya.continuum.messaging.transport.MessageTransport;
import lol.candyriya.continuum.messaging.transport.Subscription;
import lol.candyriya.continuum.messaging.transport.SubscriptionException;
import lol.candyriya.continuum.messaging.transport.TransportStats;
import lol.candyriya.continuum.messaging.events.BackboneEvents;
import lol.candyriya.continuum.messaging.events.RecoveryRequiredPayload;
import lol.candyriya.continuum.messaging.events.SessionCreatedPayload;
import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.storage.api.OutboxEntry;
import lol.candyriya.continuum.storage.api.SessionRecord;
import lol.candyriya.continuum.storage.api.StorageConfig;
import lol.candyriya.continuum.storage.recovery.OrphanedSession;
import lol.candyriya.continuum.storage.recovery.RecoveryManager;
import lol.candyriya.continuum.storage.recovery.SqlOrphanedSessionFinder;
import lol.candyriya.continuum.storage.sql.SqlPlayerStorage;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared coverage for the SQL outbox claimers and the orphaned-session scan,
 * run once per engine. Requires docker; concrete subclasses skip the whole
 * class cleanly when the docker daemon cannot be reached.
 *
 * <p>Tests never assume a clean outbox beyond "drained": every test drains or
 * filters by fresh player UUIDs first.</p>
 */
abstract class AbstractSqlOutboxClaimerIntegrationTest {

    private static final SerializationConfig SERIALIZATION = new SerializationConfig(false, 4 * 1024 * 1024);
    private static final StorageConfig STORAGE = StorageConfig.defaults();
    private static final MysqlPool POOL = new MysqlPool(8, 8, Duration.ofSeconds(2), Duration.ofMinutes(25));

    protected abstract DatabaseType engine();

    protected abstract TestDatabase connection();

    record TestDatabase(String host, int port, String database, String username, String password) {
    }

    /**
     * Mirror of the storage layer's JDBC url (its own copy is package-private).
     * Cross-engine properties only, utf8mb4_unicode_ci for portability.
     */
    private String jdbcUrl() {
        TestDatabase db = connection();
        String scheme = engine() == DatabaseType.MARIADB ? "jdbc:mariadb" : "jdbc:mysql";
        return scheme + "://" + db.host() + ":" + db.port() + "/" + db.database()
                + "?cachePrepStmts=true&prepStmtCacheSize=250"
                + "&connectionCollation=utf8mb4_unicode_ci"
                + "&tcpKeepAlive=true";
    }

    private MysqlSettings settings() {
        TestDatabase db = connection();
        return new MysqlSettings(db.host(), db.port(), db.database(), db.username(), db.password(), POOL);
    }

    protected SqlPlayerStorage openStorage() {
        return SqlPlayerStorage.open(settings(), engine(), SERIALIZATION, STORAGE);
    }

    protected HikariDataSource openPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl());
        config.setUsername(connection().username());
        config.setPassword(connection().password());
        config.setMaximumPoolSize(4);
        HikariDataSource pool = new HikariDataSource(config);
        return pool;
    }

    private void rawUpdate(String sql, Object... binds) throws SQLException {
        TestDatabase db = connection();
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(), db.username(), db.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                statement.setObject(i + 1, binds[i]);
            }
            statement.executeUpdate();
        }
    }

    private List<String> rawStringColumn(String sql, Object... binds) throws SQLException {
        TestDatabase db = connection();
        List<String> out = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(), db.username(), db.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                statement.setObject(i + 1, binds[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private void drainOutbox(SqlPlayerStorage storage) {
        storage.publishOutbox(1000, Integer.MAX_VALUE, entry -> true);
    }

    // --- claimer basics ---------------------------------------------------------

    @Test
    void claimReturnsOldestFirstAndMapsRows() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.claim.a", player, "{\"seq\":1}");
            storage.appendOutbox("test.claim.b", player, "{\"seq\":2}");
            storage.appendOutbox("test.claim.c", player, "{\"seq\":3}");
        }
        try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool())) {
            List<OutboxEvent> claimed = claimer.claimBatch(10);
            List<String> types = claimed.stream().map(OutboxEvent::eventType)
                    .filter(type -> type.startsWith("test.claim.")).toList();
            assertEquals(List.of("test.claim.a", "test.claim.b", "test.claim.c"), types);
            for (OutboxEvent event : claimed) {
                assertEquals(player.toString(), event.correlationId());
                assertEquals(0, event.attempts());
                assertNotNull(event.createdAt());
            }
            claimer.markPublished(claimed.stream().map(OutboxEvent::eventId).toList());
        }
    }

    @Test
    void claimedRowsAreInvisibleToACompetingClaimer() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.skip.a", player, "{}");
            storage.appendOutbox("test.skip.b", player, "{}");
            storage.appendOutbox("test.skip.c", player, "{}");
        }
        try (SqlOutboxClaimer first = new SqlOutboxClaimer(openPool());
             SqlOutboxClaimer second = new SqlOutboxClaimer(openPool())) {
            List<OutboxEvent> held = first.claimBatch(2).stream()
                    .filter(event -> event.eventType().startsWith("test.skip.")).toList();
            assertEquals(2, held.size(), "the first claim should hold two rows");

            List<OutboxEvent> competitor = second.claimBatch(10).stream()
                    .filter(event -> event.eventType().startsWith("test.skip.")).toList();
            assertEquals(List.of("test.skip.c"), competitor.stream().map(OutboxEvent::eventType).toList(),
                    "SKIP LOCKED must make held rows invisible to a competing claimer");

            first.markPublished(held.stream().map(OutboxEvent::eventId).toList());
            second.markPublished(competitor.stream().map(OutboxEvent::eventId).toList());
        }
    }

    @Test
    void markPublishedIsIdempotent() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.idem", player, "{}");
        }
        try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool())) {
            List<OutboxEvent> claimed = claimer.claimBatch(10);
            List<UUID> ids = claimed.stream().map(OutboxEvent::eventId).toList();
            claimer.markPublished(ids);
            claimer.markPublished(ids); // re-mark must be a no-op, not an error
        }
        try (SqlPlayerStorage storage = openStorage()) {
            assertTrue(storage.pollOutbox(10).stream().noneMatch(entry -> entry.playerId().equals(player)));
        }
    }

    @Test
    void markFailedBumpsAttemptsTruncatesErrorAndKeepsRowClaimable() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.fail", player, "{}");
        }
        try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool())) {
            OutboxEvent event = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventType().equals("test.fail"))
                    .findFirst().orElseThrow();
            claimer.markFailed(List.of(event.eventId()), "x".repeat(600));
        }
        try (SqlPlayerStorage storage = openStorage()) {
            OutboxEntry failed = storage.pollOutbox(10).stream()
                    .filter(entry -> entry.playerId().equals(player)).findFirst().orElseThrow();
            assertEquals(1, failed.attempts());
            assertNotNull(failed.lastError());
            assertEquals(512, failed.lastError().length());
            drainOutbox(storage);
        }
    }

    @Test
    void rowsAtMaxAttemptsAreNeverClaimed() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.budget", player, "{}");
        }
        try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool(), 1)) {
            OutboxEvent event = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventType().equals("test.budget"))
                    .findFirst().orElseThrow();
            claimer.markFailed(List.of(event.eventId()), "boom");
            List<OutboxEvent> again = claimer.claimBatch(10).stream()
                    .filter(candidate -> candidate.eventType().equals("test.budget")).toList();
            assertTrue(again.isEmpty(), "a row at the attempt budget must not be claimed anymore");
        } finally {
            try (SqlPlayerStorage storage = openStorage()) {
                drainOutbox(storage);
            }
        }
    }

    @Test
    void abandonedClaimReturnsRowsToTheOutbox() {
        UUID player = UUID.randomUUID();
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox("test.abandon.a", player, "{}");
            storage.appendOutbox("test.abandon.b", player, "{}");
        }
        SqlOutboxClaimer abandoned = new SqlOutboxClaimer(openPool());
        List<OutboxEvent> held = abandoned.claimBatch(10).stream()
                .filter(event -> event.eventType().startsWith("test.abandon.")).toList();
        assertEquals(2, held.size());
        abandoned.close(); // simulates a relay that died between claim and outcome

        try (SqlOutboxClaimer fresh = new SqlOutboxClaimer(openPool())) {
            List<OutboxEvent> reclaimed = fresh.claimBatch(10).stream()
                    .filter(event -> event.eventType().startsWith("test.abandon.")).toList();
            assertEquals(List.of("test.abandon.a", "test.abandon.b"),
                    reclaimed.stream().map(OutboxEvent::eventType).toList(),
                    "an abandoned claim must roll back and free the rows");
            fresh.markPublished(reclaimed.stream().map(OutboxEvent::eventId).toList());
        } finally {
            try (SqlPlayerStorage storage = openStorage()) {
                drainOutbox(storage);
            }
        }
    }

    @Test
    void concurrentClaimersNeverClaimTheSameRow() throws Exception {
        UUID player = UUID.randomUUID();
        int rows = 12;
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            for (int i = 0; i < rows; i++) {
                storage.appendOutbox("test.race." + i, player, "{}");
            }
        }
        ConcurrentLinkedQueue<UUID> claimedEventIds = new ConcurrentLinkedQueue<>();
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread worker = Thread.ofPlatform().name("claimer-race-" + i).start(() -> {
                try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool())) {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    for (int attempt = 0; attempt < 20; attempt++) {
                        List<OutboxEvent> batch = claimer.claimBatch(4).stream()
                                .filter(event -> event.eventType().startsWith("test.race.")).toList();
                        if (batch.isEmpty()) {
                            return;
                        }
                        for (OutboxEvent event : batch) {
                            claimedEventIds.add(event.eventId());
                        }
                        claimer.markPublished(batch.stream().map(OutboxEvent::eventId).toList());
                    }
                } catch (Exception e) {
                    throw new AssertionError("racing claimer failed", e);
                }
            });
            workers.add(worker);
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        Set<UUID> unique = new HashSet<>(claimedEventIds);
        assertEquals(claimedEventIds.size(), unique.size(), "no event may be claimed by two workers");
        assertEquals(rows, unique.size(), "every row must have been claimed exactly once");
        try (SqlPlayerStorage storage = openStorage()) {
            assertTrue(storage.pollOutbox(100).stream().noneMatch(entry -> entry.playerId().equals(player)));
        }
    }

    // --- relay end to end -----------------------------------------------------------

    @Test
    void relayClaimsPublishesAndMarksAgainstTheSameOutbox() throws Exception {
        UUID player = UUID.randomUUID();
        UUID sessionHandle = lol.candyriya.continuum.storage.recovery.RecoveryManager.sessionUuidFor(42L);

        EnvelopeCodec codec = new EnvelopeCodec(BackboneEvents.create(), 16 * 1024);
        SessionCreatedPayload payload = new SessionCreatedPayload(player, sessionHandle, "survival-1");
        MessageEnvelope<SessionCreatedPayload> envelope =
                MessageEnvelope.create(BackboneEvents.SESSION_CREATED, player.toString(), payload);
        String frame = new String(codec.encode(envelope), StandardCharsets.UTF_8);

        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            storage.appendOutbox(BackboneEvents.SESSION_CREATED, player, frame);
        }

        CapturingTransport transport = new CapturingTransport();
        try (SqlOutboxClaimer claimer = new SqlOutboxClaimer(openPool());
             OutboxRelay relay = new OutboxRelay(claimer, transport, codec,
                     new OutboxRelayConfig(Duration.ofMillis(200), 10, Duration.ofSeconds(5)))) {
            relay.start();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                if (!transport.published().isEmpty()) {
                    break;
                }
                Thread.sleep(50);
            }
        }

        assertEquals(1, transport.published().size(), "the relay must publish exactly one frame");
        MessageEnvelope<? extends lol.candyriya.continuum.common.protocol.EventPayload> published =
                transport.published().get(0);
        assertEquals(BackboneEvents.SESSION_CREATED, published.eventType());

        try (SqlPlayerStorage storage = openStorage()) {
            assertTrue(storage.pollOutbox(10).stream()
                    .noneMatch(entry -> entry.playerId().equals(player) && entry.publishedAt() == null),
                    "the relay must mark the row published after the broker confirm");
        }
    }

    // --- orphaned session scan -----------------------------------------------------

    @Test
    void orphanedSessionIsFoundMarkedAndRecoveredIdempotently() throws Exception {
        UUID player = UUID.randomUUID();
        SessionRecord session;
        try (SqlPlayerStorage storage = openStorage()) {
            drainOutbox(storage);
            session = storage.acquireSession(player, "recovery-backend");
            // force the lease into the past to simulate a dead owner
            rawUpdate("UPDATE sessions SET lease_expires_at = ? WHERE player_uuid = ? AND token = ?",
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(10),
                    player.toString(), session.token());
        }

        try (HikariDataSource pool = openPool()) {
            List<OrphanedSession> orphans = new SqlOrphanedSessionFinder(pool).findOrphans(Instant.now(), 100);            assertTrue(orphans.stream().anyMatch(orphan -> orphan.playerId().equals(player)
                            && orphan.sessionId() == session.sessionId()),
                    "the expired session must be found");

            try (SqlPlayerStorage storage = openStorage()) {
            RecoveryManager manager = new RecoveryManager(storage,
                    new SqlOrphanedSessionFinder(pool),
                    new lol.candyriya.continuum.common.config.RecoveryConfig(true, Duration.ofMillis(50), java.util.List.of()));
            // a shared database may hold orphans from earlier failed runs; recoverOnce sweeps them too,
            // so assert on my session rather than on the exact count
            assertTrue(manager.recoverOnce() >= 1, "recovery must mark the orphaned session");

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

            assertEquals(0,
                    new SqlOrphanedSessionFinder(pool).findOrphans(Instant.now(), 100)
                            .stream().filter(orphan -> orphan.playerId().equals(player)).count(),
                    "a marked session is no longer orphaned");
            drainOutbox(storage);
            }
        }
    }

    /** A transport that records published frames instead of hitting a broker. */
    private static final class CapturingTransport implements MessageTransport {

        private final List<MessageEnvelope<? extends lol.candyriya.continuum.common.protocol.EventPayload>> published =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final TransportStats stats = new TransportStats();

        List<MessageEnvelope<? extends lol.candyriya.continuum.common.protocol.EventPayload>> published() {
            return List.copyOf(published);
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> publish(
                MessageEnvelope<? extends lol.candyriya.continuum.common.protocol.EventPayload> envelope) {
            stats.incrementPublished();
            published.add(envelope);
            stats.incrementConfirmed();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public Subscription subscribe(String queueName, String bindingKey,
                                      lol.candyriya.continuum.messaging.transport.MessageHandler handler) {
            throw new SubscriptionException("this transport does not consume");
        }

        @Override
        public TransportStats stats() {
            return stats;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> close() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
