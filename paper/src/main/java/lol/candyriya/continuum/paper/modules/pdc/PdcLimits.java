package lol.candyriya.continuum.paper.modules.pdc;

/**
 * The size budgets the PDC module enforces. PDC bloat is a real footgun:
 * every entry sits in the player's {@code .dat} file and slows down every save.
 *
 * <p>Two independent caps:</p>
 * <ul>
 *   <li>{@link #PER_KEY_BYTES} - a single captured value may not exceed this. An
 *       oversized value is skipped, the rest of the snapshot is still produced.</li>
 *   <li>{@link #TOTAL_BYTES} - the whole payload may not exceed this. Exceeding it
 *       refuses the snapshot outright; half a snapshot is worse than none.</li>
 * </ul>
 *
 * <p>Sizes are decoded footprints, not JSON length: a 64 KiB byte array counts as
 * 64 KiB, not as ~87 KiB of base64. The snapshot layer stores the JSON afterwards,
 * so the wire size will be somewhat larger - acceptable overhead, the caps exist to
 * stop abuse, not to be exact.</p>
 */
public final class PdcLimits {

    /** Maximum decoded bytes per single value: 64 KiB. */
    public static final long PER_KEY_BYTES = 64L * 1024;

    /** Maximum decoded bytes across all captured values: 1 MiB. */
    public static final long TOTAL_BYTES = 1024L * 1024;

    /** Longest namespace segment in a namespaced key, matching the vanilla limit. */
    public static final int MAX_NAMESPACE_LENGTH = 64;

    /** Longest path segment in a namespaced key, matching the vanilla limit. */
    public static final int MAX_PATH_LENGTH = 256;

    private PdcLimits() {
        // constants only
    }
}
