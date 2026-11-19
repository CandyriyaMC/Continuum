package lol.candyriya.continuum.paper.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts consecutive restore failures per player and enforces the login refusal
 * cooldown once the configured limit is tripped.
 *
 * <p>Semantics: failures are consecutive — a successful restore resets the
 * streak entirely. When the streak reaches {@code maxFailures}, logins are
 * refused for {@code cooldownMinutes} counted from that failure, and the
 * streak restarts from zero (the next failure counts again).</p>
 *
 * <p>Memory hygiene: entries are reaped once their streak is zero and any
 * cooldown expired. The map is capped — an attacker cycling throwaway accounts
 * cannot grow it without bound; once full, new players are simply not tracked
 * (their failures are logged, never silently dropped).</p>
 */
final class FailureTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(FailureTracker.class);
    private static final int MAX_ENTRIES = 4_096;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        private final AtomicInteger consecutive = new AtomicInteger();
        private volatile Instant cooldownUntil;
    }

    /** Outcome of recording one failure; {@code streak} is the streak including this failure. */
    record FailureResult(int streak, boolean tripped) {
    }

    /**
     * Records a failure.
     *
     * @return true when this failure tripped the refusal cooldown (streak reached {@code maxFailures})
     */
    boolean recordFailure(UUID playerId, int maxFailures, Duration cooldown) {
        return recordFailureDetailed(playerId, maxFailures, cooldown).tripped();
    }

    /**
     * Records a failure and reports the resulting streak alongside the cooldown trip.
     * The streak reported is the one this failure created — when the trip reset it,
     * that reset does not leak into the result.
     */
    FailureResult recordFailureDetailed(UUID playerId, int maxFailures, Duration cooldown) {
        Entry entry = entries.get(playerId);
        if (entry == null) {
            if (entries.size() >= MAX_ENTRIES && !entries.containsKey(playerId)) {
                LOGGER.warn("failure tracker is full ({} entries); not tracking player {} — refusal cooling is unavailable for them",
                        MAX_ENTRIES, playerId);
                return new FailureResult(0, false);
            }
            entry = entries.computeIfAbsent(playerId, _ -> new Entry());
        }
        int streak = entry.consecutive.incrementAndGet();
        if (streak >= maxFailures) {
            entry.cooldownUntil = Instant.now().plus(cooldown);
            entry.consecutive.set(0);
            LOGGER.warn("player {} hit {} consecutive restore failures; logins refused for {} minutes",
                    playerId, streak, cooldown.toMinutes());
            return new FailureResult(streak, true);
        }
        return new FailureResult(streak, false);
    }

    /** A successful restore: the streak and any active cooldown both reset. */
    void recordSuccess(UUID playerId) {
        entries.remove(playerId);
    }

    /** True when the player is currently refused logins by the cooldown. */
    boolean isRefused(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry == null) {
            return false;
        }
        Instant until = entry.cooldownUntil;
        return until != null && Instant.now().isBefore(until);
    }

    /** Milliseconds until the refusal ends; 0 when the player is not refused. */
    long refusedMillisRemaining(UUID playerId) {
        Entry entry = entries.get(playerId);
        if (entry == null) {
            return 0;
        }
        Instant until = entry.cooldownUntil;
        if (until == null) {
            return 0;
        }
        long remaining = Duration.between(Instant.now(), until).toMillis();
        return Math.max(remaining, 0);
    }

    /** Drops entries with a zero streak and no active cooldown. Called periodically by the gate. */
    void reap() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(e ->
                e.getValue().consecutive.get() == 0 && (e.getValue().cooldownUntil == null || !now.isBefore(e.getValue().cooldownUntil)));
    }

    /** Entries currently held; diagnostics only. */
    int size() {
        return entries.size();
    }
}
