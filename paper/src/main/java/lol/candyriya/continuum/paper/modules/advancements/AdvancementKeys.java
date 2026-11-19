package lol.candyriya.continuum.paper.modules.advancements;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Pure validator for namespaced keys as used by advancements (and everything else in
 * the registry): {@code namespace:path}, lowercase, mirroring what
 * {@link org.bukkit.NamespacedKey#fromString} accepts, plus the 256-character total
 * bound of the underlying resource location. No platform calls — safe to use in tests
 * and in the restore planner.
 *
 * <p>Rules (identical to the vanilla resource location grammar):
 * namespace matches {@code [a-z0-9_.-]+}, path matches {@code [a-z0-9/._-]+}, exactly
 * one colon separates them, and the whole string is at most {@value #MAX_KEY_LENGTH}
 * characters. Empty namespace or path is rejected.</p>
 */
public final class AdvancementKeys {

    private AdvancementKeys() {
    }

    /** Maximum total length of a namespaced key; matches the vanilla resource location bound. */
    public static final int MAX_KEY_LENGTH = 256;

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.\\-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._\\-]+");

    /**
     * Checks whether {@code key} is a syntactically valid namespaced key.
     *
     * @param key the candidate, may be null
     * @return true when the key is well-formed
     */
    public static boolean isValid(@Nullable String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        int sep = key.indexOf(':');
        if (sep <= 0 || sep == key.length() - 1) {
            // no colon, a leading colon, or an empty path
            return false;
        }
        if (key.indexOf(':', sep + 1) != -1) {
            // a second colon can never be valid in either part
            return false;
        }
        return NAMESPACE.matcher(key.substring(0, sep)).matches() && PATH.matcher(key.substring(sep + 1)).matches();
    }

    /**
     * Trims and returns {@code key} when valid.
     *
     * @param key the candidate, never null
     * @return the trimmed key, never null
     * @throws IllegalArgumentException when the key is not valid
     */
    public static @NotNull String requireValid(@NotNull String key) {
        String trimmed = key.trim();
        if (!isValid(trimmed)) {
            throw new IllegalArgumentException("not a valid namespaced key: '" + key + "'");
        }
        return trimmed;
    }
}
