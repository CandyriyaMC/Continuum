package lol.candyriya.continuum.paper.modules.custom;

/**
 * Size governance for the custom module's payload.
 *
 * @param perAdapterCapBytes cap for one adapter's serialized payload; an adapter beyond it is
 *                           recorded as failed (or aborted, per its failure policy) — default {@value #DEFAULT_PER_ADAPTER_CAP_BYTES}
 * @param totalCapBytes      cap for the whole custom module payload; beyond it capture fails hard
 *                           (no snapshot is persisted) — default {@value #DEFAULT_TOTAL_CAP_BYTES}
 * @since 1.0.0
 */
public record CustomAdapterLimits(int perAdapterCapBytes, int totalCapBytes) {

    /** Default per-adapter cap: 64 KiB. */
    public static final int DEFAULT_PER_ADAPTER_CAP_BYTES = 64 * 1024;

    /** Default total cap: 256 KiB. */
    public static final int DEFAULT_TOTAL_CAP_BYTES = 256 * 1024;

    /** The shipping defaults: 64 KiB per adapter, 256 KiB in total. */
    public static final CustomAdapterLimits DEFAULT =
            new CustomAdapterLimits(DEFAULT_PER_ADAPTER_CAP_BYTES, DEFAULT_TOTAL_CAP_BYTES);

    public CustomAdapterLimits {
        if (perAdapterCapBytes < 1) {
            throw new IllegalArgumentException("perAdapterCapBytes must be >= 1, got " + perAdapterCapBytes);
        }
        if (totalCapBytes < 1) {
            throw new IllegalArgumentException("totalCapBytes must be >= 1, got " + totalCapBytes);
        }
        if (totalCapBytes < perAdapterCapBytes) {
            throw new IllegalArgumentException(
                    "totalCapBytes (" + totalCapBytes + ") must be >= perAdapterCapBytes (" + perAdapterCapBytes + ")");
        }
    }
}
