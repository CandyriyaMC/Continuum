package lol.candyriya.continuum.velocity.session;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window transition counter used to spot reconnect storms.
 *
 * <p>Kept deliberately dumb and free of proxy types so it can be tested with a
 * hand-rolled clock. Pruning happens on write; reads prune on the spot too so
 * stale entries never skew a decision.</p>
 */
final class ReconnectTracker {

    private final ConcurrentHashMap<UUID, ArrayDeque<Instant>> transitions = new ConcurrentHashMap<>();

    /**
     * Records a transition at {@code when} and returns how many transitions the
     * player racked up inside {@code windowMillis}, this one included.
     */
    int record(UUID id, Instant when, long windowMillis) {
        ArrayDeque<Instant> deque = transitions.computeIfAbsent(id, key -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, when, windowMillis);
            deque.addLast(when);
            return deque.size();
        }
    }

    /** Transitions inside the window ending at {@code when}, without recording anything. */
    int countInWindow(UUID id, Instant when, long windowMillis) {
        ArrayDeque<Instant> deque = transitions.get(id);
        if (deque == null) {
            return 0;
        }
        synchronized (deque) {
            prune(deque, when, windowMillis);
            return deque.size();
        }
    }

    void remove(UUID id) {
        transitions.remove(id);
    }

    void clear() {
        transitions.clear();
    }

    boolean isEmpty() {
        return transitions.isEmpty();
    }

    private static void prune(ArrayDeque<Instant> deque, Instant when, long windowMillis) {
        long horizon = when.toEpochMilli() - windowMillis;
        while (!deque.isEmpty() && deque.peekFirst().toEpochMilli() < horizon) {
            deque.pollFirst();
        }
    }
}
