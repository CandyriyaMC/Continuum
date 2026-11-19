package lol.candyriya.continuum.paper.modules.attributes;

import java.util.regex.Pattern;

/**
 * Key handling for anything stored in the payload (attribute keys, modifier
 * keys). All stored keys must carry an explicit namespace — a bare {@code max_health}
 * is not accepted, because the wire format never implies one and we do not want
 * silent {@code minecraft:} rewrites on the read path.
 *
 * <p>Self-contained validator mirroring NamespacedKey's documented character
 * rules ({@code [a-z0-9_-.]} for the namespace, {@code [a-z0-9_-./]} for the value),
 * with a hard length cap so a garbage payload cannot force huge allocations.
 * Kept bukkit-free so it can be tested without a server.</p>
 */
final class AttributeKeys {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_./-]+");
    private static final int MAX_TOTAL_LENGTH = Short.MAX_VALUE;

    private AttributeKeys() {
    }

    /**
     * True when {@code key} is a namespaced key we are willing to persist or apply:
     * exactly one {@code ':'}, both sides present, valid characters, no length games.
     * Case-sensitive: stored keys are lowercase, anything else is rejected rather
     * than normalized.
     */
    static boolean isValid(String key) {
        if (key == null || key.length() > MAX_TOTAL_LENGTH) {
            return false;
        }
        int separator = key.indexOf(':');
        if (separator <= 0 || separator == key.length() - 1) {
            return false;
        }
        if (key.indexOf(':', separator + 1) != -1) {
            return false;
        }
        return NAMESPACE.matcher(key.substring(0, separator)).matches()
                && VALUE.matcher(key.substring(separator + 1)).matches();
    }

    /** The namespace part of {@code key}, or an empty string when absent or malformed. */
    static String namespaceOf(String key) {
        if (key == null) {
            return "";
        }
        int separator = key.indexOf(':');
        return separator <= 0 ? "" : key.substring(0, separator);
    }
}
