package lol.candyriya.continuum.paper.modules.statistics;

import java.util.List;

/**
 * Wire payload for {@code continuum.statistics} (schema {@link #FORMAT}).
 *
 * <p>Serialized by the snapshot layer as JSON through the shared Continuum
 * mapper (snake_case keys). Component names are part of the stable wire
 * contract — renaming one is a schema bump. Example payload (abbreviated):</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "stats": [
 *     { "key": "untyped:deaths", "value": 12 },
 *     { "key": "item:use_item:diamond_sword", "value": 3401 }
 *   ]
 * }
 * }</pre>
 *
 * <p><b>Cumulative semantics.</b> Statistics are monotonic counters. The
 * stored values are never subtracted from anything on the write path, and the
 * restore path only ever raises the local counter (catch-up) — a local counter
 * ahead of the snapshot wins. See {@link StatisticsModule} for the full
 * reconciliation contract. Because of that, entries with value {@code 0} carry
 * no information and are omitted from the payload: a missing key is
 * indistinguishable from a zero in every downstream decision.</p>
 *
 * <p><b>Size.</b> A typical player has tens to a few hundred non-zero cells.
 * The theoretical ceiling is bounded by the slot catalog (every untyped stat,
 * item/block stats × qualifier, entity stats × entity types) — roughly
 * 65–70k entries today, growing slowly with each Minecraft release; each
 * entry is ~40–55 bytes of JSON, so even that extreme case stays well within
 * snapshot size limits. Real snapshots are orders of magnitude smaller.</p>
 *
 * @param format payload format version, always {@link #FORMAT}
 * @param stats  the non-zero counters, keyed by qualified key; never null, copied defensively
 */
public record StatisticsPayload(int format, List<Entry> stats) {

    /** Current wire format. Bump when the serialized shape changes incompatibly. */
    public static final int FORMAT = 1;

    /**
     * Structural ceiling on entries in a payload — roughly four times today's
     * full catalog, leaving headroom for future Minecraft content while still
     * rejecting garbage before it gets near the stat grid. Validation rejects
     * anything larger; it is not a target, just a backstop.
     */
    public static final int MAX_ENTRIES = 262_144;

    /**
     * One counter cell.
     *
     * @param key   the qualified key, see {@link StatKey}
     * @param value the cumulative counter value; negative is never legitimate
     */
    public record Entry(String key, long value) {
    }

    public StatisticsPayload {
        stats = stats == null ? List.of() : List.copyOf(stats);
    }
}
