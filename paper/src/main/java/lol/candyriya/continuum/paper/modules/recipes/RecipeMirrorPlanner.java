package lol.candyriya.continuum.paper.modules.recipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Exact-replica diff between the captured recipe book and the local one.
 *
 * <p>The restore contract is a <em>mirror</em>: after restore the player's
 * known recipes on this server must be the captured set, filtered by what
 * the target server actually ships. That yields two batched operations:
 * discover what is missing, and forget what was locally discovered but is
 * absent from the capture — otherwise a recipe forgotten on the source
 * server would keep living on every other backend, and "forgotten recipes"
 * would never propagate.</p>
 *
 * <p>The alternative (union: discover missing, never forget) is weaker but
 * additive-only; it is deliberately not used — see the module javadoc.</p>
 *
 * <p>Effective set definition: {@code effective = captured ∩ targetKnown}.
 * Captured keys the target does not know are reported and skipped (modded
 * vs vanilla mismatch — the key is preserved in future snapshots untouched,
 * this restore just cannot act on it). The forget side is computed against
 * the same effective set, so a captured key unknown to the target never
 * causes a local undiscover of a related entry, and a local key unknown to
 * the target is reported and skipped (the book cannot reference a recipe
 * the server does not have).</p>
 *
 * <p>Pure logic, no platform calls. {@code targetKnows} is the server's
 * recipe-map predicate; matching is plain string equality after strict
 * validation. Deterministic: every output list is sorted.</p>
 */
final class RecipeMirrorPlanner {

    private RecipeMirrorPlanner() {
        // static access only
    }

    /**
     * Computes the mirror plan.
     *
     * @param captured    raw captured entries from the payload; order and duplicates do not matter,
     *                    malformed entries are reported and excluded, never thrown on
     * @param local       raw local discovered keys straight from the entity
     * @param targetKnows predicate over the exact captured string, true when the target server ships
     *                    a recipe under that key; exceptions propagate (a broken lookup is a bug,
     *                    not something to route around)
     * @return the plan to apply, fully sorted and deterministic
     */
    static MirrorPlan plan(List<String> captured, Set<String> local, Predicate<String> targetKnows) {
        TreeSet<String> unique = new TreeSet<>();
        List<String> invalid = new ArrayList<>();
        int duplicates = 0;
        for (String key : captured) {
            if (!RecipeKeys.isValid(key)) {
                invalid.add(key == null ? "<null>" : key);
                continue;
            }
            if (!unique.add(key)) {
                duplicates++;
            }
        }

        List<String> localSorted = RecipeKeys.normalizeLocal(local);

        // split the captured set by target availability, then do straight set algebra
        TreeSet<String> effective = new TreeSet<>();
        List<String> unknownToTarget = new ArrayList<>();
        for (String key : unique) {
            if (targetKnows.test(key)) {
                effective.add(key);
            } else {
                unknownToTarget.add(key);
            }
        }

        TreeSet<String> localSet = new TreeSet<>(localSorted);

        TreeSet<String> toDiscover = new TreeSet<>(effective);
        toDiscover.removeAll(localSet);

        // forget only what the target knows (otherwise undiscover is a no-op anyway),
        // and only what the capture does not list — exact-replica mirror.
        // no predicate re-evaluation for keys the capture already vetted: if it is in the
        // captured set yet not in effective, the target simply does not know it — same bucket.
        TreeSet<String> toForget = new TreeSet<>();
        List<String> forgetSkippedUnknown = new ArrayList<>();
        for (String key : localSorted) {
            if (effective.contains(key)) {
                continue; // stays learned, obviously
            }
            if (unique.contains(key)) {
                forgetSkippedUnknown.add(key); // captured above but unknown to this server
                continue;
            }
            if (targetKnows.test(key)) {
                toForget.add(key);
            } else {
                forgetSkippedUnknown.add(key);
            }
        }

        return new MirrorPlan(
                List.copyOf(toDiscover),
                List.copyOf(toForget),
                List.copyOf(unknownToTarget),
                List.copyOf(forgetSkippedUnknown),
                List.copyOf(invalid),
                duplicates);
    }
}
