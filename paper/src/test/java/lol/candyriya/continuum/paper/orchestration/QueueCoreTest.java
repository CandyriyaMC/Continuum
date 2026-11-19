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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure state-machine tests for the lane bookkeeping — no threads, no executors.
 * The threaded behavior is covered by {@link PlayerFifoQueueTest}.
 */
class QueueCoreTest {

    private static QueueCore core(int capacity, Duration idle) {
        return new QueueCore(capacity, idle, Clock.systemUTC());
    }

    private static QueueCore core(int capacity, Duration idle, Clock clock) {
        return new QueueCore(capacity, idle, clock);
    }

    private static QueueCore.Accepted enqueueAccepted(QueueCore core, UUID player, FifoOperation<String> op) {
        QueueCore.EnqueueResult result = core.enqueue(player, op, new CompletableFuture<>());
        return assertInstanceOf(QueueCore.Accepted.class, result);
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

    @Test
    void firstSubmissionSchedulesTheDrainAndTheRestDoNot() {
        QueueCore core = core(4, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();

        QueueCore.Accepted first = enqueueAccepted(core, player, () -> "a");
        QueueCore.Accepted second = enqueueAccepted(core, player, () -> "b");

        assertTrue(first.scheduleDrain());
        assertFalse(second.scheduleDrain());
    }

    @Test
    void laneRefusesBeyondCapacityAndCompletesTheFutureExceptionally() {
        QueueCore core = core(2, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();

        CompletableFuture<String> third = new CompletableFuture<>();
        core.enqueue(player, () -> "a", new CompletableFuture<>());
        core.enqueue(player, () -> "b", new CompletableFuture<>());
        QueueCore.EnqueueResult rejected = core.enqueue(player, () -> "c", third);

        assertInstanceOf(QueueCore.RefusedFull.class, rejected);
        assertTrue(third.isCompletedExceptionally());
        assertEquals(2, core.pendingCount(player));
    }

    @Test
    void lanesForDifferentPlayersAreIndependent() {
        QueueCore core = core(1, Duration.ofMinutes(1));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        core.enqueue(first, () -> "a", new CompletableFuture<>());
        core.enqueue(first, () -> "a2", new CompletableFuture<>());
        QueueCore.EnqueueResult firstFull = core.enqueue(first, () -> "a3", new CompletableFuture<>());
        QueueCore.EnqueueResult secondAccepted = core.enqueue(second, () -> "b", new CompletableFuture<>());

        assertInstanceOf(QueueCore.RefusedFull.class, firstFull);
        assertInstanceOf(QueueCore.Accepted.class, secondAccepted);
    }

    @Test
    void pollOrReleaseHandsOutItemsInSubmissionOrder() {
        QueueCore core = core(8, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();
        List<String> executed = new ArrayList<>();

        QueueCore.Accepted first = enqueueAccepted(core, player, () -> {
            executed.add("one");
            return null;
        });
        core.enqueue(player, () -> {
            executed.add("two");
            return null;
        }, new CompletableFuture<>());

        QueueCore.Lane lane = first.lane();
        QueueCore.Item<?> one = core.pollOrRelease(lane);
        QueueCore.Item<?> two = core.pollOrRelease(lane);
        QueueCore.Item<?> empty = core.pollOrRelease(lane);

        one.run();
        two.run();
        assertEquals(List.of("one", "two"), executed);
        assertNull(empty);
        assertEquals(0, core.pendingCount(player));
    }

    @Test
    void drainWindowClosesAtomicallyWhenTheLaneGoesEmpty() {
        QueueCore core = core(8, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();

        enqueueAccepted(core, player, () -> "x");
        enqueueAccepted(core, player, () -> "y");
        QueueCore.Lane lane = enqueueAccepted(core, player, () -> "z").lane();

        core.pollOrRelease(lane);
        core.pollOrRelease(lane);
        core.pollOrRelease(lane);
        assertNull(core.pollOrRelease(lane), "lane goes empty here: the drain window closes");

        QueueCore.EnqueueResult afterIdle = core.enqueue(player, () -> "w", new CompletableFuture<>());
        assertTrue(((QueueCore.Accepted) afterIdle).scheduleDrain(),
                "submission after the drain window closes must schedule a fresh drain");
    }

    @Test
    void idleEmptyLanesAreEvictedAndTouchedOnesAreNot() {
        MutableClock clock = new MutableClock(1_000_000);
        QueueCore core = core(8, Duration.ofMinutes(5), clock);
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();

        // both lanes take one op and run it to empty
        QueueCore.Accepted staleOp = enqueueAccepted(core, stale, () -> "x");
        core.pollOrRelease(staleOp.lane());
        core.pollOrRelease(staleOp.lane());
        QueueCore.Accepted freshOp = enqueueAccepted(core, fresh, () -> "y");
        core.pollOrRelease(freshOp.lane());
        core.pollOrRelease(freshOp.lane());

        clock.advance(Duration.ofMinutes(6));
        // fresh sees activity again after the jump: its last-active moves forward
        QueueCore.Accepted freshAgain = enqueueAccepted(core, fresh, () -> "y2");
        core.pollOrRelease(freshAgain.lane());
        core.pollOrRelease(freshAgain.lane());

        int evicted = core.evictIdleNow();

        assertEquals(1, evicted);
        assertFalse(core.isTracked(stale));
        assertTrue(core.isTracked(fresh));
    }

    @Test
    void busyLanesAreNeverEvicted() {
        MutableClock clock = new MutableClock(1_000_000);
        QueueCore core = core(8, Duration.ofMinutes(5), clock);
        UUID player = UUID.randomUUID();

        enqueueAccepted(core, player, () -> "x");
        clock.advance(Duration.ofMinutes(6));

        assertEquals(0, core.evictIdleNow());
        assertTrue(core.isTracked(player));
    }

    @Test
    void closeRefusesNewOperationsAndFlushPendingFailsWhatIsLeft() {
        QueueCore core = core(4, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();

        CompletableFuture<String> doomed = new CompletableFuture<>();
        core.enqueue(player, () -> "never runs", doomed);
        core.beginClose();

        QueueCore.EnqueueResult refused = core.enqueue(player, () -> "x", new CompletableFuture<>());
        assertInstanceOf(QueueCore.RefusedClosed.class, refused);

        int flushed = core.flushPending("test close");

        assertEquals(1, flushed);
        assertTrue(doomed.isCompletedExceptionally());
        assertThrows(ExecutionException.class, () -> doomed.get(1, TimeUnit.SECONDS));
        assertEquals(0, core.trackedLanes());
    }

    @Test
    void cancelAcceptedRemovesTheSpecificItemAndReopensTheDrainWindow() {
        QueueCore core = core(4, Duration.ofMinutes(1));
        UUID player = UUID.randomUUID();

        QueueCore.Accepted accepted = enqueueAccepted(core, player, () -> "x");
        assertTrue(core.cancelAccepted(accepted.lane(), accepted.item(), accepted.scheduleDrain()));

        QueueCore.EnqueueResult next = core.enqueue(player, () -> "y", new CompletableFuture<>());
        assertTrue(((QueueCore.Accepted) next).scheduleDrain());
    }
}
