package lol.candyriya.continuum.messaging.pluginmessage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PendingRequestsTest {

    private static final RouteResultMessage REPLY =
            new RouteResultMessage(UUID.randomUUID(), RouteOutcome.ALLOWED, "survival-1", "ok");

    @Test
    void registerAndCompleteReturnsReply() throws Exception {
        try (PendingRequests table = new PendingRequests()) {
            PendingRequest request = table.register();
            assertEquals(1, table.size());

            assertTrue(table.complete(request.correlationId(), REPLY));
            assertEquals(0, table.size());
            assertSame(REPLY, request.response().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void completeUnknownCorrelationReturnsFalse() {
        try (PendingRequests table = new PendingRequests()) {
            assertFalse(table.complete(UUID.randomUUID(), REPLY));
            assertFalse(table.fail(UUID.randomUUID(), new RuntimeException("nope")));
        }
    }

    @Test
    void doubleCompleteIsRefusedTheSecondTime() {
        try (PendingRequests table = new PendingRequests()) {
            PendingRequest request = table.register();
            assertTrue(table.complete(request.correlationId(), REPLY));
            assertFalse(table.complete(request.correlationId(), REPLY));
            assertEquals(0, table.size());
        }
    }

    @Test
    void timeoutCompletesExceptionallyAndEvicts() throws Exception {
        try (PendingRequests table = new PendingRequests(8, Duration.ofMillis(150))) {
            PendingRequest request = table.register();
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> request.response().get(5, TimeUnit.SECONDS));
            assertInstanceOf(PendingRequestTimeoutException.class, e.getCause());
            assertEquals(0, table.size());
        }
    }

    @Test
    void timeoutExceptionCarriesCorrelationAndTimeout() throws Exception {
        try (PendingRequests table = new PendingRequests(8, Duration.ofMillis(150))) {
            PendingRequest request = table.register();
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> request.response().get(5, TimeUnit.SECONDS));
            PendingRequestTimeoutException cause = (PendingRequestTimeoutException) e.getCause();
            assertEquals(request.correlationId(), cause.correlationId());
            assertEquals(Duration.ofMillis(150), cause.timeout());
        }
    }

    @Test
    void tableIsBoundedByTheConfiguredCap() {
        try (PendingRequests table = new PendingRequests(2, Duration.ofSeconds(30))) {
            table.register();
            table.register();
            assertThrows(PendingRequestLimitException.class, table::register);
            assertEquals(2, table.size());
        }
    }

    @Test
    void evictionFreesCapacity() {
        try (PendingRequests table = new PendingRequests(1, Duration.ofSeconds(30))) {
            PendingRequest first = table.register();
            assertThrows(PendingRequestLimitException.class, table::register);
            assertTrue(table.complete(first.correlationId(), REPLY));
            assertDoesNotThrow(() -> { table.register(); });
        }
    }

    @Test
    void explicitCorrelationIdIsRespected() {
        try (PendingRequests table = new PendingRequests()) {
            UUID id = UUID.randomUUID();
            PendingRequest request = table.register(id);
            assertEquals(id, request.correlationId());
        }
    }

    @Test
    void duplicateCorrelationIdIsRejected() {
        try (PendingRequests table = new PendingRequests()) {
            UUID id = UUID.randomUUID();
            table.register(id);
            DuplicateCorrelationIdException e = assertThrows(DuplicateCorrelationIdException.class,
                    () -> table.register(id));
            assertEquals(id, e.correlationId());
            assertEquals(1, table.size());
        }
    }

    @Test
    void failPropagatesTheCause() throws Exception {
        try (PendingRequests table = new PendingRequests()) {
            PendingRequest request = table.register();
            RuntimeException boom = new RuntimeException("peer exploded");
            assertTrue(table.fail(request.correlationId(), boom));
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> request.response().get(5, TimeUnit.SECONDS));
            assertSame(boom, e.getCause());
            assertEquals(0, table.size());
        }
    }

    @Test
    void replyWinsOverTimeout() throws Exception {
        // generous margins so CI machines cannot flake
        try (PendingRequests table = new PendingRequests(8, Duration.ofMillis(400))) {
            PendingRequest request = table.register();
            Thread.sleep(50);
            assertTrue(table.complete(request.correlationId(), REPLY));
            assertSame(REPLY, request.response().get(5, TimeUnit.SECONDS));
            assertEquals(0, table.size());
            Thread.sleep(600); // let the sweeper fire; it must not corrupt anything
            assertSame(REPLY, request.response().getNow(null));
        }
    }

    @Test
    void timeoutWinsOverLateReply() throws Exception {
        try (PendingRequests table = new PendingRequests(8, Duration.ofMillis(150))) {
            PendingRequest request = table.register();
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> request.response().get(5, TimeUnit.SECONDS));
            assertInstanceOf(PendingRequestTimeoutException.class, e.getCause());
            // a reply that arrives after the timeout finds nothing in the table
            assertFalse(table.complete(request.correlationId(), REPLY));
            assertEquals(0, table.size());
        }
    }

    @Test
    void concurrentCompletesExactlyOneWins() throws Exception {
        for (int round = 0; round < 50; round++) {
            try (PendingRequests table = new PendingRequests()) {
                PendingRequest request = table.register();
                UUID id = request.correlationId();
                List<Boolean> outcomes = IntStream.range(0, 8)
                        .parallel()
                        .mapToObj(i -> table.complete(id, REPLY))
                        .toList();
                assertEquals(1, outcomes.stream().filter(won -> won).count(),
                        "round " + round + ": exactly one completing call must win");
                assertEquals(0, table.size());
                assertSame(REPLY, request.response().get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void concurrentRegistersRespectTheCap() {
        int cap = 4;
        int threads = 16;
        try (PendingRequests table = new PendingRequests(cap, Duration.ofSeconds(30))) {
            List<Boolean> results = IntStream.range(0, threads)
                    .parallel()
                    .mapToObj(i -> {
                        try {
                            table.register();
                            return true;
                        } catch (PendingRequestLimitException e) {
                            return false;
                        }
                    })
                    .toList();
            assertEquals(cap, results.stream().filter(ok -> ok).count());
            assertEquals(cap, table.size());
        }
    }

    @Test
    void closeFailsAllPendingAndRejectsNew() throws Exception {
        PendingRequests table = new PendingRequests();
        PendingRequest first = table.register();
        PendingRequest second = table.register();
        table.close();
        assertThrows(ExecutionException.class, () -> first.response().get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> second.response().get(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, table::register);
        assertEquals(0, table.size());
        table.close(); // idempotent
    }

    @Test
    void closeIsIdempotentOnAnEmptyTable() {
        PendingRequests table = new PendingRequests();
        assertDoesNotThrow(table::close);
        assertDoesNotThrow(table::close);
    }

    @Test
    void rejectsNullAndInvalidArguments() {
        try (PendingRequests table = new PendingRequests()) {
            assertThrows(NullPointerException.class, () -> table.register(null));
            assertThrows(NullPointerException.class, () -> table.complete(UUID.randomUUID(), null));
            assertThrows(NullPointerException.class, () -> table.fail(UUID.randomUUID(), null));
            PendingRequest request = table.register();
            assertThrows(NullPointerException.class, () -> table.complete(null, REPLY));
            assertThrows(NullPointerException.class, () -> table.fail(null, new RuntimeException()));
            assertDoesNotThrow(() -> { table.complete(request.correlationId(), REPLY); });
        }
    }

    @Test
    void rejectsBadConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(-1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(8, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(8, Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> new PendingRequests(8, null));
    }

    @Test
    void defaultsMatchTheConfigurationContract() {
        assertEquals(1024, PendingRequests.DEFAULT_MAX_PENDING);
        assertEquals(Duration.ofSeconds(10), PendingRequests.DEFAULT_TIMEOUT);

        try (PendingRequests table = new PendingRequests()) {
            assertEquals(1024, table.maxPending());
            assertEquals(Duration.ofSeconds(10), table.timeout());
        }
    }

    @Test
    void replyFutureCompletesBeforeSweeperWouldEverFire() throws Exception {
        // end-to-end: register like a backend would, reply like the proxy would
        try (PendingRequests table = new PendingRequests(16, Duration.ofSeconds(5))) {
            PendingRequest ping = table.register();
            PongMessage pong = new PongMessage(Instant.ofEpochMilli(1_700_000_000_000L));
            assertTrue(table.complete(ping.correlationId(), pong));
            assertSame(pong, ping.response().get(5, TimeUnit.SECONDS));
        }
    }
}
