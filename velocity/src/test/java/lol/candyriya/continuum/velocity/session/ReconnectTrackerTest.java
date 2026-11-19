package lol.candyriya.continuum.velocity.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectTrackerTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_000_000);

    private final ReconnectTracker tracker = new ReconnectTracker();

    @Test
    void firstTransitionCountsOne() {
        UUID player = UUID.randomUUID();
        int count = tracker.record(player, T0, Duration.ofSeconds(10).toMillis());
        assertEquals(1, count);
    }

    @Test
    void transitionsAccumulateInsideWindow() {
        UUID player = UUID.randomUUID();
        long window = Duration.ofSeconds(10).toMillis();
        tracker.record(player, T0, window);
        tracker.record(player, T0.plusMillis(2_000), window);
        int count = tracker.record(player, T0.plusMillis(4_000), window);
        assertEquals(3, count);
    }

    @Test
    void transitionsFallOutsideWindowAndStopCounting() {
        UUID player = UUID.randomUUID();
        long window = Duration.ofSeconds(10).toMillis();
        tracker.record(player, T0, window);
        tracker.record(player, T0.plusMillis(1_000), window);
        // this one is 30s later; the old ones must be pruned
        int count = tracker.record(player, T0.plusMillis(31_000), window);
        assertEquals(1, count);
        assertEquals(1, tracker.countInWindow(player, T0.plusMillis(31_000), window));
    }

    @Test
    void countInWindowDoesNotRecord() {
        UUID player = UUID.randomUUID();
        long window = Duration.ofSeconds(10).toMillis();
        assertEquals(0, tracker.countInWindow(player, T0, window));
        tracker.record(player, T0, window);
        assertEquals(1, tracker.countInWindow(player, T0, window));
        // reading must not consume or add anything
        assertEquals(1, tracker.countInWindow(player, T0, window));
    }

    @Test
    void removeDropsAllTraces() {
        UUID player = UUID.randomUUID();
        tracker.record(player, T0, Duration.ofSeconds(10).toMillis());
        tracker.remove(player);
        assertTrue(tracker.isEmpty());
        assertEquals(0, tracker.countInWindow(player, T0, Duration.ofSeconds(10).toMillis()));
    }

    @Test
    void differentPlayersAreIndependent() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        long window = Duration.ofSeconds(10).toMillis();
        tracker.record(one, T0, window);
        tracker.record(one, T0.plusMillis(1_000), window);
        tracker.record(two, T0.plusMillis(1_000), window);
        assertEquals(2, tracker.countInWindow(one, T0.plusMillis(1_000), window));
        assertEquals(1, tracker.countInWindow(two, T0.plusMillis(1_000), window));
    }
}
