package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.storage.api.OutboxPublisher;
import lol.candyriya.continuum.storage.recovery.InMemoryPlayerStorage;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweeper behavior against the in-memory storage: convergence, poison rows,
 * graceful shutdown, disabled mode.
 */
class StorageOutboxSweeperTest {

    @Test
    void publishesPendingEventsThroughTheStoragePath() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        storage.appendOutbox("test.sweep.a", player, "{}");
        storage.appendOutbox("test.sweep.b", player, "{}");

        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, entry -> true,
                Duration.ofSeconds(60), 100, 10, true);
        sweeper.sweepOnce();

        assertEquals(2, sweeper.stats().published());
        assertEquals(0, storage.pollOutbox(10).size());
        assertEquals(1, sweeper.stats().sweeps());
        assertEquals(0, sweeper.stats().claimFailures());
    }

    @Test
    void failingPublisherLeavesRetryableRowsAndTheSweeperSurvives() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        storage.appendOutbox("test.retry", player, "{}");

        AtomicBoolean brokerHealthy = new AtomicBoolean(false);
        OutboxPublisher flaky = candidate -> brokerHealthy.get();

        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, flaky,
                Duration.ofSeconds(60), 100, 3, true);

        sweeper.sweepOnce();
        sweeper.sweepOnce();
        assertEquals(0, sweeper.stats().published());
        assertEquals(2, storage.pollOutbox(10).get(0).attempts(), "each failed sweep must bump attempts");

        brokerHealthy.set(true);
        sweeper.sweepOnce();
        assertEquals(1, sweeper.stats().published());
        assertEquals(0, storage.pollOutbox(10).size());
    }

    @Test
    void rowsPastTheAttemptBudgetAreSetAsideAndLoggedOnce() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        storage.appendOutbox("test.poison", player, "{}");

        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, entry -> false,
                Duration.ofSeconds(60), 100, 2, true);

        sweeper.sweepOnce(); // attempts 0 -> 1
        sweeper.sweepOnce(); // attempts 1 -> 2, now at the budget
        assertFalse(storage.pollOutbox(10).isEmpty(), "row is at budget but unpublished");

        sweeper.sweepOnce(); // must not publish, must notice the set-aside row
        sweeper.sweepOnce(); // and must not re-log it
        assertEquals(0, sweeper.stats().published());
        assertEquals(1, sweeper.stats().setAside(), "the set-aside row must be counted exactly once");

        // a success-flavored publisher must not resurrect the set-aside row either
        StorageOutboxSweeper retried = new StorageOutboxSweeper(storage, entry -> true,
                Duration.ofSeconds(60), 100, 2, true);
        retried.sweepOnce();
        assertEquals(0, retried.stats().published(), "rows past maxAttempts are never claimed again");
    }

    @Test
    void throwingPublisherDoesNotKillTheLoop() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        storage.appendOutbox("test.throw", player, "{}");

        OutboxPublisher boom = entry -> {
            throw new IllegalStateException("broker exploded");
        };

        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, boom,
                Duration.ofSeconds(60), 100, 10, true);
        sweeper.sweepOnce();
        sweeper.sweepOnce();
        assertEquals(2, storage.pollOutbox(10).get(0).attempts(), "each throwing sweep must count as a failed attempt");
        assertEquals(0, sweeper.stats().claimFailures());
    }

    @Test
    void disabledSweeperNeverStarts() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, entry -> true,
                Duration.ofSeconds(60), 100, 10, false);
        sweeper.start();
        assertFalse(sweeper.running());
        sweeper.close();
    }

    @Test
    void startedSweeperPublishesInTheBackgroundAndStopsCleanly() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        storage.appendOutbox("test.background", player, "{}");

        StorageOutboxSweeper sweeper = new StorageOutboxSweeper(storage, entry -> true,
                Duration.ofMillis(50), 100, 10, true);
        sweeper.start();
        assertTrue(sweeper.running());
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !storage.pollOutbox(10).isEmpty()) {
            Thread.sleep(25);
        }
        sweeper.close();
        assertEquals(0, storage.pollOutbox(10).size(), "the background loop must have drained the outbox");
        assertFalse(sweeper.running());
    }

    @Test
    void fromRecoveryConfigHonorsEnabledAndInterval() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        var config = new lol.candyriya.continuum.common.config.RecoveryConfig(
                false, Duration.ofSeconds(7), List.of());
        StorageOutboxSweeper sweeper = StorageOutboxSweeper.fromRecoveryConfig(storage, entry -> true, config);
        sweeper.start();
        assertFalse(sweeper.running(), "disabled recovery config must keep the sweeper off");
    }
}
