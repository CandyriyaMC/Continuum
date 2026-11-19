package lol.candyriya.continuum.paper.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFifoQueueTest {

    private static OrchestrationSettings settings(int capacity) {
        return new OrchestrationSettings(capacity, 4, Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30));
    }

    @Test
    void operationsForOnePlayerRunInSubmissionOrder() throws Exception {
        // capacity must fit the whole burst — the drain may legitimately lag the
        // submitting loop on a loaded machine, and we are not testing backpressure here
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(64))) {
            UUID player = UUID.randomUUID();
            List<Integer> executionOrder = new ArrayList<>();
            List<CompletableFuture<?>> pending = new ArrayList<>();

            for (int i = 0; i < 32; i++) {
                int value = i;
                pending.add(fifo.submit(player, () -> {
                    synchronized (executionOrder) {
                        executionOrder.add(value);
                    }
                    return null;
                }));
            }
            for (CompletableFuture<?> future : pending) {
                future.get(5, TimeUnit.SECONDS);
            }

            assertEquals(32, executionOrder.size());
            for (int i = 0; i < 32; i++) {
                assertEquals(i, executionOrder.get(i));
            }
        }
    }

    @Test
    void operationsForDifferentPlayersRunInParallel() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(16))) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            CountDownLatch bothInside = new CountDownLatch(2);

            CompletableFuture<?> a = fifo.submit(first, () -> {
                bothInside.countDown();
                if (!bothInside.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("lanes did not overlap — serialization leaked across players");
                }
                return "a";
            });
            CompletableFuture<?> b = fifo.submit(second, () -> {
                bothInside.countDown();
                if (!bothInside.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("lanes did not overlap — serialization leaked across players");
                }
                return "b";
            });

            assertEquals("a", a.get(5, TimeUnit.SECONDS));
            assertEquals("b", b.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void fullQueueRefusesSubmissionsAndLogs() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(2))) {
            UUID player = UUID.randomUUID();
            CountDownLatch release = new CountDownLatch(1);

            // first operation holds the lane; the queue fills behind it
            fifo.submit(player, () -> {
                release.await(5, TimeUnit.SECONDS);
                return null;
            });
            fifo.submit(player, () -> null);
            fifo.submit(player, () -> null);
            // this one exceeds the capacity of 2 pending + 1 running... capacity counts pending only,
            // running one is already polled out of the deque — pending may hold 2, third is refused
            CompletableFuture<?> third = fifo.submit(player, () -> null);

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> third.get(2, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof FifoQueueFullException,
                    "expected FifoQueueFullException, got " + e.getCause());
            release.countDown();
        }
    }

    @Test
    void failingOperationsCompleteTheActorExceptionallyAndTheLaneKeepsMoving() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(8))) {
            UUID player = UUID.randomUUID();

            CompletableFuture<?> failing = fifo.submit(player, () -> {
                throw new IllegalStateException("kaput");
            });
            CompletableFuture<String> after = fifo.submit(player, () -> "still alive");

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> failing.get(5, TimeUnit.SECONDS));
            assertEquals("kaput", e.getCause().getMessage());
            assertEquals("still alive", after.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void idleLanesAreEvictedAfterTheConfiguredIdleTime() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(8))) {
            UUID player = UUID.randomUUID();
            fifo.submit(player, () -> "done").get(5, TimeUnit.SECONDS);
            assertTrue(fifo.isTracked(player));

            Thread.sleep(150);
            fifo.evictIdleNow();

            assertFalse(fifo.isTracked(player));
            assertEquals(0, fifo.trackedLanes());
        }
    }

    @Test
    void activeLanesAreNotEvicted() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(8))) {
            UUID player = UUID.randomUUID();
            CountDownLatch park = new CountDownLatch(1);
            CompletableFuture<?> blocker = fifo.submit(player, () -> {
                park.await(5, TimeUnit.SECONDS);
                return null;
            });
            Thread.sleep(50);

            fifo.evictIdleNow();

            assertTrue(fifo.isTracked(player));
            park.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closedQueueRefusesNewSubmissions() {
        PlayerFifoQueue fifo = new PlayerFifoQueue(settings(8));
        UUID player = UUID.randomUUID();
        fifo.close();

        CompletableFuture<?> future = fifo.submit(player, () -> null);

        assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void pendingCountReflectsQueuedWork() throws Exception {
        try (PlayerFifoQueue fifo = new PlayerFifoQueue(settings(8))) {
            UUID player = UUID.randomUUID();
            CountDownLatch park = new CountDownLatch(1);
            fifo.submit(player, () -> {
                park.await(5, TimeUnit.SECONDS);
                return null;
            });
            fifo.submit(player, () -> null);

            // one is running/parked, one is pending — implementation tolerates either count 0..2
            assertTrue(fifo.pendingCount(player) >= 1, "expected at least one pending op");
            park.countDown();
        }
    }
}
