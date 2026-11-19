package lol.candyriya.continuum.paper.modules.advancements;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What to do when the target server does not know an advancement.
 *
 * <p>Only {@link #WARN_AND_SKIP} is implemented today. The platform config schema
 * currently carries a per-module on/off switch only, so there is no enum to read a
 * richer policy from without forking the config. The constant is single-valued on
 * purpose: when the common schema learns an unknown-advancement mode, the candidates
 * ({@code SKIP}, {@code WARN_AND_SKIP}, {@code SAVE_FOR_LATER}) land here and the
 * restore path reads exactly one constant. Do not add behavior here before the config
 * carries it.</p>
 */
enum AdvancementUnknownPolicy {
    /** Log a warning and skip the advancement; the rest of the restore proceeds untouched. */
    WARN_AND_SKIP
}

/**
 * Pure restore-planning logic for the advancements module: no platform types, no I/O,
 * no clock access — everything arrives as arguments, so the whole policy is
 * unit-testable without a server.
 *
 * <p>Restore semantics, in order:</p>
 * <ol>
 *   <li>A captured key that fails {@link AdvancementKeys} is skipped as
 *       {@link Action#SKIP_MALFORMED_KEY}; the lookup is never consulted for it.</li>
 *   <li>An advancement the target server does not know ({@link TargetLookup} yields
 *       empty) is skipped as {@link Action#SKIP_UNKNOWN_ADVANCEMENT} under
 *       {@link AdvancementUnknownPolicy#WARN_AND_SKIP}: the caller logs a warning and
 *       moves on. One missing advancement never aborts the restore.</li>
 *   <li>Captured criteria that do not exist on the target (newer/older data pack on
 *       this node) are dropped and reported in {@link Decision#unknownCriteria()};
 *       the remainder is still restored.</li>
 *   <li>The set to award is {@code (captured awarded ∩ target criteria) −
 *       currently awarded}, sorted alphabetically. Already-awarded criteria are never
 *       re-awarded and never revoked — restore is award-only by design.</li>
 *   <li>Decisions are sorted by advancement key, so application order is deterministic
 *       regardless of payload order.</li>
 * </ol>
 *
 * <p>Completion state is never set directly. Vanilla derives it from criteria, so
 * awarding converges the advancement on its own; there is nothing else to write.</p>
 */
public final class AdvancementsRestorePlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancementsRestorePlanner.class);

    private AdvancementsRestorePlanner() {
    }

    /** What to do with a single captured advancement. */
    public enum Action {
        /** Award the criteria in {@link Decision#toAward()} (target derives completion from them). */
        APPLY,
        /** Nothing to award: everything captured is already awarded on the target, or there was nothing to begin with. */
        NONE,
        /** The target server does not have this advancement; see {@link AdvancementUnknownPolicy}. */
        SKIP_UNKNOWN_ADVANCEMENT,
        /** The captured key is not a syntactically valid namespaced key. */
        SKIP_MALFORMED_KEY
    }

    /**
     * The target server's view of one advancement, plain data — the platform adapter in
     * the module fills this from {@code Advancement}/{@code AdvancementProgress}.
     *
     * @param criteria the criteria the advancement declares on this server
     * @param awarded  the criteria already awarded to the player here
     * @param done     whether the advancement is complete here right now
     */
    public record TargetAdvancement(@NotNull Set<String> criteria, @NotNull Set<String> awarded, boolean done) {
        public TargetAdvancement {
            criteria = Set.copyOf(criteria);
            awarded = Set.copyOf(awarded);
        }
    }

    /** Resolves an advancement key against this server. Returns empty when the advancement is unknown here. */
    @FunctionalInterface
    public interface TargetLookup {
        /**
         * @param advancementKey a syntactically valid namespaced key (the planner rejects malformed ones first)
         * @return the target's view of the advancement, or empty when this server does not have it
         */
        @NotNull Optional<@NotNull TargetAdvancement> resolve(@NotNull String advancementKey);
    }

    /**
     * What to do with one captured advancement.
     *
     * @param key             the namespaced advancement key from the snapshot
     * @param action          the decision
     * @param toAward         criteria to award on the target (subset of the target's criteria, not yet awarded), sorted
     * @param unknownCriteria captured criteria the target does not declare; dropped at restore, sorted
     * @param alreadyAwarded  how many of the captured-and-known criteria were already awarded on the target
     * @param doneCaptured    the done state recorded at capture time
     * @param targetDone      the done state on the target right now (false for unknown/malformed entries)
     */
    public record Decision(@NotNull String key, @NotNull Action action,
                           @NotNull @Unmodifiable List<String> toAward,
                           @NotNull @Unmodifiable List<String> unknownCriteria,
                           int alreadyAwarded,
                           boolean doneCaptured,
                           boolean targetDone) {
    }

    /**
     * The full restore plan for a payload.
     *
     * @param decisions one decision per captured entry, sorted by key
     */
    public record Plan(@NotNull @Unmodifiable List<Decision> decisions) {

        /** Number of decisions with the given action. */
        public long count(@NotNull Action action) {
            return decisions.stream().filter(d -> d.action() == action).count();
        }

        /** Total criteria to award across all decisions. */
        public long totalToAward() {
            return decisions.stream().mapToLong(d -> d.toAward().size()).sum();
        }

        /** Total captured criteria dropped because the target does not declare them. */
        public long totalUnknownCriteria() {
            return decisions.stream().mapToLong(d -> d.unknownCriteria().size()).sum();
        }
    }

    /**
     * Computes the restore plan for {@code payload}.
     *
     * @param payload the decoded snapshot payload, never null
     * @param lookup  resolves captured keys against this server
     * @return the plan; never null
     */
    public static @NotNull Plan plan(@NotNull AdvancementsPayload payload, @NotNull TargetLookup lookup) {
        var decisions = new ArrayList<Decision>(payload.advancements().size());
        for (AdvancementEntry entry : payload.advancements()) {
            decisions.add(decide(entry, resolveQuietly(lookup, entry.key())));
        }
        decisions.sort(Comparator.comparing(Decision::key));
        return new Plan(List.copyOf(decisions));
    }

    /**
     * Decides what to do with a single entry.
     *
     * @param entry  the captured entry
     * @param target the target's view of the advancement, empty when the target does not know it (or the lookup failed)
     * @return the decision; never null
     */
    public static @NotNull Decision decide(@NotNull AdvancementEntry entry, @NotNull Optional<TargetAdvancement> target) {
        String key = entry.key();
        if (!AdvancementKeys.isValid(key)) {
            return new Decision(key, Action.SKIP_MALFORMED_KEY, List.of(), List.of(), 0, entry.done(), false);
        }
        if (target.isEmpty()) {
            return new Decision(key, Action.SKIP_UNKNOWN_ADVANCEMENT, List.of(), List.of(), 0, entry.done(), false);
        }
        TargetAdvancement view = target.get();
        var captured = new TreeSet<>(entry.awardedCriteria());
        var unknown = captured.stream().filter(c -> !view.criteria().contains(c)).toList();
        var toAward = captured.stream()
                .filter(view.criteria()::contains)
                .filter(c -> !view.awarded().contains(c))
                .toList();
        int already = (int) captured.stream()
                .filter(view.criteria()::contains)
                .filter(view.awarded()::contains)
                .count();
        Action action = toAward.isEmpty() ? Action.NONE : Action.APPLY;
        return new Decision(key, action, toAward, unknown, already, entry.done(), view.done());
    }

    /**
     * Wraps the lookup so a throwing or hostile lookup is treated as "unknown on this
     * server" for just this entry instead of aborting the whole restore, and is never
     * consulted for a malformed key.
     */
    private static @NotNull Optional<TargetAdvancement> resolveQuietly(@NotNull TargetLookup lookup, @NotNull String key) {
        if (!AdvancementKeys.isValid(key)) {
            return Optional.empty();
        }
        try {
            return lookup.resolve(key);
        } catch (RuntimeException e) {
            // one registry hiccup must not take the whole restore with it
            LOGGER.warn("lookup for advancement '{}' failed; treating it as unknown on this server", key, e);
            return Optional.empty();
        }
    }
}
