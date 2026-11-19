package lol.candyriya.continuum.paper.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Threaded invariants for {@link QueueCore}. The state machine is covered by
 * {@link QueueCoreTest}; this is the "many threads race on one lane" side of
 * the contract, using the pure class only, no platform executor.
 *
 * <p>The drain loop mirrors what {@code PlayerFifoQueue} does per lane: any
 * caller whose enqueue came back with {@code scheduleDrain = true} keeps
 * polling until the core reports the lane empty. Two threads may never drain
 * the same lane at the same time; {@code pollOrRelease} is the arbiter.</p>
 */
class QueueCoreConcurrencyTest {

    private static QueueCore core(int capacity) {
        return new QueueCore(capacity, Duration.ofMinutes(1), Clock.systemUTC());
    }

    /** Runs the drain loop exactly the way PlayerFifoQueue does. */
    private static void drain(QueueCore core, QueueCore.Accepted accepted) {
        while (true) {
            QueueCore.Item<?> item = core.pollOrRelease(accepted.lane());
            if (item == null) {
                return;
            }
            item.run();
        }
    }

    @Test
    void opQueuedBehindARunningOneObservesItFullyBeforeStarting() throws Exception {
        QueueCore core = core(8);
        UUID player = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new ArrayList<>();

        // op1 holds the lane; op2 lands in the queue behind it
        CompletableFuture<String> firstFuture = new CompletableFuture<>();
        QueueCore.Accepted first = (QueueCore.Accepted) core.enqueue(player, () -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS), "test parking latch was not released");
            synchronized (order) {
                order.add("first");
            }
            return "first";
        }, firstFuture);

        CompletableFuture<String> secondFuture = new CompletableFuture<>();

        Thread driver = new Thread(() -> drain(core, first), "lane-driver");
        driver.start();
        assertTrue(started.await(2, TimeUnit.SECONDS), "first op never started");

        QueueCore.EnqueueResult behindResult = core.enqueue(player, () -> {
            synchronized (order) {
                order.add("second");
            }
            return "second";
        }, secondFuture);
        assertInstanceOf(QueueCore.Accepted.class, behindResult,
                "op behind a busy lane must still be accepted (it is queued, not refused)");
        assertFalse(((QueueCore.Accepted) behindResult).scheduleDrain(),
                "the running drain owns the lane; this op must not open a second drain window");

        release.countDown();
        driver.join(2_000);

        assertEquals(List.of("first", "second"), order);
        assertEquals("first", firstFuture.get(2, TimeUnit.SECONDS));
        assertEquals("second", secondFuture.get(2, TimeUnit.SECONDS));
    }

    @Test
    void racingSubmissionsOnOneLaneAreMutuallyExclusiveAndCompleteExactlyOnce() throws Exception {
        QueueCore core = core(64);
        UUID player = UUID.randomUUID();
        int threads = 16;

        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ConcurrentHashMap<Integer, AtomicInteger> executions = new ConcurrentHashMap<>();
        AtomicReference<AssertionError> invariantBreach = new AtomicReference<>();

        List<CompletableFuture<?>> futures = new ArrayList<>();
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            CompletableFuture<String> future = new CompletableFuture<>();
            futures.add(future);
            pool.add(new Thread(() -> {
                try {
                    go.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                QueueCore.EnqueueResult result = core.enqueue(player, () -> {
                    int now = running.incrementAndGet();
                    maxRunning.accumulateAndGet(now, Math::max);
                    if (now != 1) {
                        invariantBreach.compareAndSet(null, new AssertionError(
                                "op " + index + " entered the lane while another op was running (active=" + now + ")"));
                    }
                    // hold the lane for a random slice to widen the race window
                    try {
                        Thread.sleep(ThreadLocalRandom.current().nextInt(0, 3));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    executions.computeIfAbsent(index, k -> new AtomicInteger()).incrementAndGet();
                    running.decrementAndGet();
                    return "op" + index;
                }, future);
                if (result instanceof QueueCore.Accepted accepted && accepted.scheduleDrain()) {
                    drain(core, accepted);
                }
            }, "submit-" + index));
        }
        for (Thread thread : pool) {
            thread.start();
        }
        go.countDown();
        for (Thread thread : pool) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "submitter thread hung");
        }

        assertNull(invariantBreach.get(), invariantBreach.get() == null ? null : invariantBreach.get().getMessage());
        assertEquals(1, maxRunning.get(), "lane must never run two ops at once");
        assertEquals(0, running.get());
        for (int i = 0; i < threads; i++) {
            assertEquals(1, executions.getOrDefault(i, new AtomicInteger()).get(),
                    "op " + i + " must run exactly once");
        }
        for (CompletableFuture<?> future : futures) {
            future.get(2, TimeUnit.SECONDS); // nothing may hang or fail
        }
        // every op ran, so the lane drained completely
        assertEquals(0, core.pendingCount(player));
    }

    @Test
    void capacityBoundaryAdmitsExactlyCapacityOpsAndRefusesOneMore() {
        QueueCore core = core(3);
        UUID player = UUID.randomUUID();

        // fill the lane to the brim: 3 accepted, nothing running yet
        List<CompletableFuture<String>> accepted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            CompletableFuture<String> future = new CompletableFuture<>();
            accepted.add(future);
            assertInstanceOf(QueueCore.Accepted.class, core.enqueue(player, () -> "op", future));
        }
        assertEquals(3, core.pendingCount(player));

        // the 4th must be refused, loudly, with the future failed — never silently dropped
        CompletableFuture<String> refused = new CompletableFuture<>();
        QueueCore.EnqueueResult result = core.enqueue(player, () -> "overflow", refused);
        assertInstanceOf(QueueCore.RefusedFull.class, result);
        assertTrue(refused.isCompletedExceptionally());
        ExecutionException e = assertThrows(ExecutionException.class, () -> refused.get(1, TimeUnit.SECONDS));
        assertInstanceOf(FifoQueueFullException.class, e.getCause());
        assertEquals(3, core.pendingCount(player), "refusal must not displace queued work");
    }

    @Test
    void capacityBoundaryOfOneAdmitsExactlyOne() {
        QueueCore core = core(1);
        UUID player = UUID.randomUUID();

        assertInstanceOf(QueueCore.Accepted.class,
                core.enqueue(player, () -> "first", new CompletableFuture<>()));
        CompletableFuture<String> refused = new CompletableFuture<>();
        assertInstanceOf(QueueCore.RefusedFull.class, core.enqueue(player, () -> "second", refused));
        assertTrue(refused.isCompletedExceptionally());
        assertEquals(1, core.pendingCount(player));
    }

    @Test
    void drainedItemLeavesTheBoundaryAndTheWindowSlides() {
        QueueCore core = core(3);
        UUID player = UUID.randomUUID();

        List<QueueCore.Accepted> fillers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int index = i;
            fillers.add((QueueCore.Accepted) core.enqueue(player, () -> "filler" + index, new CompletableFuture<>()));
        }
        assertEquals(3, core.pendingCount(player));

        // one item leaves the deque for execution; capacity opens by exactly one
        core.pollOrRelease(fillers.get(0).lane());
        assertEquals(2, core.pendingCount(player));
        assertInstanceOf(QueueCore.Accepted.class,
                core.enqueue(player, () -> "after-drain", new CompletableFuture<>()));
        assertEquals(3, core.pendingCount(player));
        // and it is full again
        assertInstanceOf(QueueCore.RefusedFull.class,
                core.enqueue(player, () -> "one-too-many", new CompletableFuture<>()));
    }

    @Test
    void closeWhileQueuedFailsEveryPendingFutureAndNeverHangs() {
        QueueCore core = core(8);
        UUID player = UUID.randomUUID();

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int index = i;
            CompletableFuture<String> future = new CompletableFuture<>();
            futures.add(future);
            core.enqueue(player, () -> "never-runs-" + index, future);
        }
        assertEquals(5, core.pendingCount(player));

        core.beginClose();
        assertInstanceOf(QueueCore.RefusedClosed.class,
                core.enqueue(player, () -> "after-close", new CompletableFuture<>()));

        int flushed = core.flushPending("node shutting down");

        assertEquals(5, flushed);
        for (CompletableFuture<String> future : futures) {
            // the close path must complete futures synchronously — no hang, ever
            assertTrue(future.isDone());
            ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertEquals("node shutting down", e.getCause().getMessage());
        }
        assertEquals(0, core.pendingCount(player));
        assertEquals(0, core.trackedLanes());
    }

    @Test
    void closeLeavesAPolledItemAloneButFailsWhatIsStillQueued() {
        QueueCore core = core(8);
        UUID player = UUID.randomUUID();

        CompletableFuture<String> inFlight = new CompletableFuture<>();
        QueueCore.Accepted accepted =
                (QueueCore.Accepted) core.enqueue(player, () -> "running", inFlight);
        CompletableFuture<String> stillQueued = new CompletableFuture<>();
        core.enqueue(player, () -> "queued", stillQueued);

        // simulate the drain claiming the first item for execution
        QueueCore.Item<?> polled = core.pollOrRelease(accepted.lane());
        assertTrue(polled != null, "the claimed item must come out of the deque");

        core.beginClose();
        int flushed = core.flushPending("closing");

        assertEquals(1, flushed, "only the queued op is failed by the close; the polled one is the executor's job");
        assertTrue(stillQueued.isCompletedExceptionally());
        assertFalse(inFlight.isDone(), "an item already claimed by a drain must not be failed by flush");
        // and the polled item still completes normally when run
        polled.run();
        assertEquals("running", inFlight.join());
    }

    @Test
    void evictionBoundaryIsExactToTheMillisecond() {
        MutableClock clock = new MutableClock(1_000_000);
        QueueCore core = new QueueCore(4, Duration.ofMinutes(5), clock);
        UUID player = UUID.randomUUID();

        // run the op to empty so the lane is evictable
        QueueCore.Accepted accepted =
                (QueueCore.Accepted) core.enqueue(player, () -> "op", new CompletableFuture<>());
        core.pollOrRelease(accepted.lane());
        core.pollOrRelease(accepted.lane()); // empty poll closes the drain window and stamps activity

        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertEquals(0, core.evictIdleNow(), "one millisecond short of the threshold the lane must survive");
        assertTrue(core.isTracked(player));

        clock.advance(Duration.ofMillis(1));
        assertEquals(1, core.evictIdleNow(), "exactly at the threshold the lane must go");
        assertFalse(core.isTracked(player));
    }

    @Test
    void busyAndDrainingLanesAreNeverEvictedEvenPastTheThreshold() {
        MutableClock clock = new MutableClock(1_000_000);
        QueueCore core = new QueueCore(4, Duration.ofMinutes(5), clock);
        UUID busy = UUID.randomUUID();
        UUID draining = UUID.randomUUID();

        // a busy lane: queued work riding out the clock jump
        core.enqueue(busy, () -> "queued", new CompletableFuture<>());
        // a lane with its op already claimed by a drain (draining window still open, deque empty)
        QueueCore.Accepted claimed =
                (QueueCore.Accepted) core.enqueue(draining, () -> "claimed", new CompletableFuture<>());
        core.pollOrRelease(claimed.lane());

        clock.advance(Duration.ofMinutes(30));

        assertEquals(0, core.evictIdleNow());
        assertTrue(core.isTracked(busy));
        assertTrue(core.isTracked(draining));
    }

    @Test
    void evictionCyclesDoNotLeakLanes() {
        MutableClock clock = new MutableClock(1_000_000);
        QueueCore core = new QueueCore(4, Duration.ofMinutes(5), clock);
        UUID player = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            QueueCore.Accepted accepted =
                    (QueueCore.Accepted) core.enqueue(player, () -> "op", new CompletableFuture<>());
            core.pollOrRelease(accepted.lane());
            core.pollOrRelease(accepted.lane());
            assertEquals(1, core.trackedLanes(), "lane table must stay flat across enqueue/drain cycles");
            clock.advance(Duration.ofMinutes(6));
            core.evictIdleNow();
            assertEquals(0, core.trackedLanes());
        }
    }

    /** A clock the test can advance by hand. */
    private static final class MutableClock extends Clock {

        private long millis;

        private MutableClock(long start) {
            this.millis = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        void advance(Duration amount) {
            millis += amount.toMillis();
        }
    }
}
