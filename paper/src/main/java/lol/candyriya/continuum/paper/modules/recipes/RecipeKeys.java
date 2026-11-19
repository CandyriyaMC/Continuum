package lol.candyriya.continuum.paper.modules.recipes;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure checks and transforms for recipe key strings.
 *
 * <p>Strict input policy for anything that crosses a trust boundary
 * (the snapshot store, the modded/vanilla mix): exactly one colon, non-empty
 * namespace and path, lowercase {@code [a-z0-9_.-]} namespace and
 * {@code [a-z0-9_./-]} path — the same rules the platform applies to
 * {@code NamespacedKey}, enforced here without touching the platform so the
 * checks run anywhere. A string passing {@link #isValid(String)} always
 * parses as a {@code NamespacedKey} with the default namespace logic never
 * kicking in (there is an explicit namespace), so the two layers cannot
 * disagree.</p>
 *
 * <p>Namespace defaults are never applied to stored keys: {@code "recipe"}
 * (no colon) is invalid, not {@code minecraft:recipe}. Silently guessing a
 * namespace on restore would be a corruption vector.</p>
 */
final class RecipeKeys {

    /** Mirrors the platform namespace rule: lowercase alphanumerics, periods, underscores, hyphens. */
    private static final String NAMESPACE = "[a-z0-9_.-]+";

    /** Mirrors the platform path rule: the namespace alphabet plus forward slashes. */
    private static final String PATH = "[a-z0-9_./-]+";

    private static final java.util.regex.Pattern NAMESPACE_PATTERN = java.util.regex.Pattern.compile(NAMESPACE);
    private static final java.util.regex.Pattern PATH_PATTERN = java.util.regex.Pattern.compile(PATH);

    /** Platform limit for the combined {@code namespace:path} length, kept from {@code NamespacedKey}. */
    private static final int MAX_KEY_LENGTH = Short.MAX_VALUE;

    private RecipeKeys() {
        // static access only
    }

    /**
     * Whether {@code key} is a well-formed {@code namespace:path} recipe key.
     *
     * @param key the raw string from a payload or the platform, may be null
     * @return true when the string is safe to store and re-parse on any server
     */
    static boolean isValid(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        int colon = key.indexOf(':');
        if (colon <= 0 || colon != key.lastIndexOf(':')) {
            // missing, leading, trailing or doubled colon — the platform would
            // happily default an empty namespace to minecraft, we don't
            return false;
        }
        return NAMESPACE_PATTERN.matcher(key.substring(0, colon)).matches()
                && PATH_PATTERN.matcher(key.substring(colon + 1)).matches();
    }

    /**
     * Validates and normalizes a captured set of discovered keys into the
     * canonical payload form: sorted, duplicate-free, every entry strict.
     *
     * <p>Capture-time failures throw instead of degrading: a discovery set the
     * module cannot faithfully store (invalid key, or more than
     * {@link RecipesPayload#MAX_RECIPES} entries — something is deeply wrong
     * with the source server) must surface as a failed capture, never as a
     * truncated or repaired snapshot.</p>
     *
     * @param discovered raw key strings straight from the entity, never null
     * @return sorted, duplicate-free, immutable list of valid keys
     * @throws IllegalArgumentException when an entry is not a valid key or the set exceeds the cap
     */
    static List<String> normalizeCapture(Collection<String> discovered) {
        if (discovered.size() > RecipesPayload.MAX_RECIPES) {
            throw new IllegalArgumentException(
                    "discovered recipe count " + discovered.size() + " exceeds the limit of " + RecipesPayload.MAX_RECIPES);
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String key : discovered) {
            if (!isValid(key)) {
                throw new IllegalArgumentException("captured invalid recipe key '" + key + "'");
            }
            sorted.add(key);
        }
        return List.copyOf(sorted);
    }

    /**
     * Defensive copy of a local discovery set for the diff math: nulls are
     * dropped, entries are sorted. Entries are not required to be valid —
     * matching against the captured set is plain string equality, so a
     * bogus local entry can never smuggle anything into the target's book.
     *
     * @param local raw set from the entity, may contain anything
     * @return sorted immutable list of non-null entries
     */
    static List<String> normalizeLocal(Collection<String> local) {
        TreeSet<String> sorted = new TreeSet<>();
        if (local != null) {
            for (String key : local) {
                if (key != null) {
                    sorted.add(key);
                }
            }
        }
        return List.copyOf(sorted);
    }

    /**
     * First duplicate encountered in {@code keys}, or null when the list is
     * duplicate-free. Used by validation to report the offender by name.
     *
     * @param keys the list to scan, elements may be anything
     * @return the duplicated string, or null
     */
    static String firstDuplicate(List<String> keys) {
        Set<String> seen = new TreeSet<>();
        for (String key : keys) {
            if (key != null && !seen.add(key)) {
                return key;
            }
        }
        return null;
    }

    /** Index of the first element violating {@link #isValid(String)}, or -1. */
    static int firstInvalidIndex(List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            if (!isValid(keys.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
