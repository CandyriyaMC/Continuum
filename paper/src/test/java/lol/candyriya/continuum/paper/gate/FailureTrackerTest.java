package lol.candyriya.continuum.paper.gate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTrackerTest {

    @Test
    void belowTheLimitDoesNotRefuse() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        assertFalse(tracker.recordFailure(player, 3, cooldown));
        assertFalse(tracker.recordFailure(player, 3, cooldown));

        assertFalse(tracker.isRefused(player));
        assertEquals(0, tracker.refusedMillisRemaining(player));
    }

    @Test
    void reachingTheLimitRefusesForTheCooldown() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        tracker.recordFailure(player, 3, cooldown);
        tracker.recordFailure(player, 3, cooldown);
        assertTrue(tracker.recordFailure(player, 3, cooldown));

        assertTrue(tracker.isRefused(player));
        long remaining = tracker.refusedMillisRemaining(player);
        assertTrue(remaining > 0 && remaining <= cooldown.toMillis(), "remaining=" + remaining);
    }

    @Test
    void aSuccessBreaksTheStreakAndTheCooldown() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        tracker.recordFailure(player, 3, cooldown);
        tracker.recordFailure(player, 3, cooldown);
        tracker.recordSuccess(player);

        // a fresh streak is needed to trip the limit again
        assertFalse(tracker.recordFailure(player, 3, cooldown));
        assertFalse(tracker.isRefused(player));
    }

    @Test
    void trippingRestartsTheStreak() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        tracker.recordFailure(player, 3, cooldown);
        tracker.recordFailure(player, 3, cooldown);
        tracker.recordFailure(player, 3, cooldown); // trips, streak resets

        // one more failure must not trip it again — the streak restarted from zero
        assertFalse(tracker.recordFailure(player, 3, cooldown));
        assertTrue(tracker.isRefused(player));
    }

    @Test
    void theDetailedResultReportsTheStreakAndTheTrip() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        FailureTracker.FailureResult first = tracker.recordFailureDetailed(player, 3, cooldown);
        FailureTracker.FailureResult second = tracker.recordFailureDetailed(player, 3, cooldown);
        FailureTracker.FailureResult third = tracker.recordFailureDetailed(player, 3, cooldown);

        assertEquals(1, first.streak());
        assertFalse(first.tripped());
        assertEquals(2, second.streak());
        assertFalse(second.tripped());
        assertEquals(3, third.streak());
        assertTrue(third.tripped());
    }

    @Test
    void refusedEntriesAreNotReapedWhileTheCooldownIsActive() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();
        Duration cooldown = Duration.ofMinutes(10);

        tracker.recordFailure(player, 1, cooldown);
        tracker.reap();

        assertEquals(1, tracker.size());
        assertTrue(tracker.isRefused(player));
    }

    @Test
    void idleEntriesAreReaped() {
        var tracker = new FailureTracker();
        UUID player = UUID.randomUUID();

        tracker.recordFailure(player, 5, Duration.ofMinutes(10)); // streak 1, no cooldown
        tracker.reap();

        assertEquals(1, tracker.size(), "entries with a live streak must survive the reaper");

        tracker.recordSuccess(player); // streak reset, entry removed
        tracker.reap();
        assertEquals(0, tracker.size());
    }

    @Test
    void trackerIsCappedAndSaysSo() {
        var tracker = new FailureTracker();
        Duration cooldown = Duration.ofMinutes(10);

        for (int i = 0; i < 4_096; i++) {
            tracker.recordFailure(UUID.randomUUID(), 5, cooldown);
        }
        UUID newcomer = UUID.randomUUID();
        // the map is full: failures of a brand-new player are not tracked
        for (int i = 0; i < 5; i++) {
            tracker.recordFailure(newcomer, 2, cooldown);
        }
        assertFalse(tracker.isRefused(newcomer));
        assertEquals(4_096, tracker.size());
    }
}
