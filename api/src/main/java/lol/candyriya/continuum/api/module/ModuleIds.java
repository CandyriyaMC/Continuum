package lol.candyriya.continuum.api.module;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * The module id convention and the checks behind it.
 *
 * <p>A module id is a dot-separated namespace of lowercase segments, for example
 * {@code continuum.inventory} or {@code myplugin.cool_module}. The rules:</p>
 * <ul>
 *   <li>two or more segments separated by single dots, no leading or trailing dot;</li>
 *   <li>each segment is 1–32 characters of {@code a-z}, {@code 0-9}, {@code _} or
 *       {@code -}, starting and ending with an alphanumeric;</li>
 *   <li>the whole id is at most 128 characters;</li>
 *   <li>ids are case-sensitive — {@code continuum.Inventory} is rejected;</li>
 *   <li>the {@code continuum} namespace is reserved for Continuum's own modules;
 *       third-party plugins must pick their own namespace (usually the plugin name,
 *       e.g. {@code myplugin.mymodule}).</li>
 * </ul>
 *
 * <p>Module ids are part of the persisted snapshot schema and of the public event model,
 * so they are effectively permanent once published: choose them deliberately, and do not
 * reuse an id for a different concept.</p>
 *
 * <p>Thread-safety: stateless utility, safe to call from any thread.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public final class ModuleIds {

    /** The namespace reserved for Continuum's first-party modules. */
    public static final String RESERVED_NAMESPACE = "continuum";

    /** Maximum length of a full module id, including dots. */
    public static final int MAX_ID_LENGTH = 128;

    /** Maximum length of a single segment. */
    public static final int MAX_SEGMENT_LENGTH = 32;

    private static final Pattern SEGMENT =
            Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,30}[a-z0-9])?");

    // at least two segments, separated by single dots
    private static final Pattern MODULE_ID =
            Pattern.compile(SEGMENT.pattern() + "(?:\\." + SEGMENT.pattern() + ")+");

    private ModuleIds() {
        // static access only
    }

    /**
     * Whether {@code id} is a well-formed module id.
     *
     * @param id the id to check, may be null
     * @return true when the id satisfies every rule above
     */
    public static boolean isValid(@Nullable String id) {
        return id != null
                && !id.isEmpty()
                && id.length() <= MAX_ID_LENGTH
                && MODULE_ID.matcher(id).matches();
    }

    /**
     * Validates {@code id}, throwing when it is not a well-formed module id.
     *
     * @param id the id to validate, may be null
     * @throws InvalidModuleIdException when the id is null or violates any rule
     */
    public static void requireValid(@Nullable String id) throws InvalidModuleIdException {
        if (id == null) {
            throw new InvalidModuleIdException(null, "must not be null");
        }
        if (id.isEmpty()) {
            throw new InvalidModuleIdException(id, "must not be empty");
        }
        if (id.length() > MAX_ID_LENGTH) {
            throw new InvalidModuleIdException(id, "must not exceed " + MAX_ID_LENGTH + " characters");
        }
        if (!MODULE_ID.matcher(id).matches()) {
            throw new InvalidModuleIdException(id,
                    "expected dotted lowercase segments like 'continuum.inventory': "
                            + "a-z, 0-9, '_', '-', 1-" + MAX_SEGMENT_LENGTH + " chars each, "
                            + "no leading/trailing dots");
        }
    }

    /**
     * The namespace part of a module id — everything before the first dot.
     *
     * @param id a well-formed module id, never null
     * @return its namespace, never null or empty
     * @throws InvalidModuleIdException when {@code id} is not well-formed
     */
    @NotNull
    public static String namespaceOf(@NotNull String id) {
        requireValid(id);
        return id.substring(0, id.indexOf('.'));
    }

    /**
     * Whether {@code id} sits in the reserved {@code continuum} namespace.
     *
     * @param id the id to check; must be well-formed, never null
     * @return true when the id belongs to Continuum's first-party namespace
     * @throws InvalidModuleIdException when {@code id} is not well-formed
     */
    public static boolean isReserved(@NotNull String id) {
        return RESERVED_NAMESPACE.equals(namespaceOf(id));
    }
}
