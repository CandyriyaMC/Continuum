package lol.candyriya.continuum.paper.modules.effects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Pure restore-planning logic for the effects module: no platform types, no I/O,
 * no clock access — everything arrives as arguments, so the whole policy is
 * unit-testable without a server.
 *
 * <p>Restore semantics, in order:</p>
 * <ol>
 *   <li>Duplicate type keys in the payload keep the <em>last</em> occurrence; the count of
 *       dropped duplicates is reported in the plan.</li>
 *   <li>Amplifier is clamped to {@code 0..255}.</li>
 *   <li>The effect type is resolved against the server's registry. Unknown or malformed
 *       keys are skipped, never fatal — one plugin effect from another backend must not
 *       abort the whole restore.</li>
 *   <li>Instant effect types are skipped (re-applying them on join would heal or hurt the
 *       player for free).</li>
 *   <li>Infinite effects ({@value EffectsPayload#INFINITE_DURATION_TICKS}) stay infinite.
 *       Otherwise remaining = duration − elapsed, where elapsed is the wall-clock time
 *       since {@code capturedAtEpochMilli} converted to ticks (50 ms per tick, floored).
 *       A remaining of zero or less means the effect ran out while the player was
 *       elsewhere and it is skipped. A capture timestamp in the future (clock skew) is
 *       clamped to zero elapsed, restoring the full duration, and flagged on the plan.</li>
 *   <li>Decisions are sorted by type key so application order is deterministic.</li>
 * </ol>
 */
public final class EffectsRestorePlanner {

    private EffectsRestorePlanner() {
    }

    /** What to do with a single snapshot entry. */
    public enum Action {
        /** Apply the effect with the remaining duration from the decision. */
        APPLY,
        /** The effect ran out while the player was away; do not re-apply. */
        SKIP_EXPIRED,
        /** The effect type is not known to this server (or the key is malformed). */
        SKIP_UNKNOWN,
        /** The effect type is instant; re-applying on join would be wrong. */
        SKIP_INSTANT
    }

    /**
     * What to do with one deduplicated snapshot entry.
     *
     * @param typeKey       the namespaced effect-type key from the snapshot
     * @param action        the decision
     * @param remainingTicks for {@link Action#APPLY}: remaining ticks, or
     *                       {@value EffectsPayload#INFINITE_DURATION_TICKS} for an infinite effect;
     *                       for skips: the computed remaining (may be &le; 0), informational only
     * @param amplifier     the clamped amplifier ({@code 0..255})
     * @param ambient       ambient flag from the snapshot
     * @param particles     particles flag from the snapshot
     * @param icon          icon flag from the snapshot
     */
    public record Decision(@NotNull String typeKey, @NotNull Action action, long remainingTicks,
                           int amplifier, boolean ambient, boolean particles, boolean icon) {
    }

    /**
     * The full restore plan for a payload.
     *
     * @param decisions        one decision per deduplicated snapshot entry, sorted by type key
     * @param clockSkewed      true when the capture timestamp was in the future; elapsed time was clamped to zero
     * @param duplicatesDropped how many snapshot entries were discarded as duplicate type keys (last wins)
     */
    public record Plan(@NotNull @Unmodifiable List<Decision> decisions, boolean clockSkewed, int duplicatesDropped) {

        /** Number of decisions with the given action. */
        public long count(@NotNull Action action) {
            return decisions.stream().filter(d -> d.action() == action).count();
        }
    }

    /**
     * Clamps an amplifier to the NBT byte range {@code 0..255}. Negative values become 0;
     * values above 255 become 255.
     */
    public static int clampAmplifier(int amplifier) {
        return Math.max(0, Math.min(255, amplifier));
    }

    /**
     * Normalizes a duration for capture.
     *
     * @param rawDurationTicks the duration reported by the server
     * @return the duration to store — {@value EffectsPayload#INFINITE_DURATION_TICKS} for infinite,
     *         the raw value for positive durations, or {@code null} when the entry must be
     *         dropped (zero or meaningless negative durations other than the infinite marker)
     */
    public static Integer normalizeDuration(int rawDurationTicks) {
        if (rawDurationTicks == EffectsPayload.INFINITE_DURATION_TICKS) {
            return EffectsPayload.INFINITE_DURATION_TICKS;
        }
        if (rawDurationTicks <= 0) {
            // zero or a negative other than the infinite marker: garbage or already over
            return null;
        }
        return rawDurationTicks;
    }

    /**
     * Collapses duplicate type keys, keeping the last occurrence. Order of first appearance
     * is preserved among distinct keys.
     */
    public static @NotNull List<EffectEntry> dedupeLastWins(@NotNull List<EffectEntry> entries) {
        var byType = new LinkedHashMap<String, EffectEntry>();
        for (EffectEntry entry : entries) {
            byType.put(entry.type(), entry);
        }
        return List.copyOf(byType.values());
    }

    /**
     * Computes the restore plan for {@code payload}.
     *
     * @param payload        the decoded snapshot payload, never null
     * @param nowEpochMilli  the restore wall-clock time, epoch milliseconds
     * @param lookup         resolves effect-type keys against this server's registry
     * @return the plan; never null
     */
    public static @NotNull Plan plan(@NotNull EffectsPayload payload, long nowEpochMilli, @NotNull EffectTypeLookup lookup) {
        var byType = new LinkedHashMap<String, EffectEntry>();
        int total = 0;
        for (EffectEntry entry : payload.effects()) {
            total++;
            byType.put(entry.type(), entry);
        }
        int duplicatesDropped = total - byType.size();

        boolean clockSkewed = nowEpochMilli < payload.capturedAtEpochMilli();
        long elapsedTicks = clockSkewed ? 0 : (nowEpochMilli - payload.capturedAtEpochMilli()) / EffectsPayload.MILLIS_PER_TICK;

        var decisions = new ArrayList<Decision>(byType.size());
        for (EffectEntry entry : byType.values()) {
            int amplifier = clampAmplifier(entry.amplifier());
            // infinite needs its own flag: a plain value of -1 could also be an overflowed remainder
            boolean infinite = entry.durationTicks() == EffectsPayload.INFINITE_DURATION_TICKS;
            long remaining = infinite
                    ? EffectsPayload.INFINITE_DURATION_TICKS
                    : (long) entry.durationTicks() - elapsedTicks;

            Optional<EffectTypeLookup.Info> info = lookup.resolve(entry.type());
            Action action;
            if (info.isEmpty()) {
                action = Action.SKIP_UNKNOWN;
            } else if (info.get().instant() || entry.instant()) {
                action = Action.SKIP_INSTANT;
            } else if (!infinite && remaining <= 0) {
                action = Action.SKIP_EXPIRED;
            } else {
                action = Action.APPLY;
            }
            decisions.add(new Decision(entry.type(), action, remaining, amplifier,
                    entry.ambient(), entry.particles(), entry.icon()));
        }

        decisions.sort((a, b) -> a.typeKey().compareTo(b.typeKey()));
        return new Plan(List.copyOf(decisions), clockSkewed, duplicatesDropped);
    }
}
