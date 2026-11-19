package lol.candyriya.continuum.paper.modules.pdc;

/**
 * Stateful budget for one capture run. Pure: no server types, no clock, no I/O.
 *
 * <p>Feed every candidate value through {@link #offer(long)} in key order. The verdict
 * tells the caller what to do; only {@link Verdict#CAPTURE} consumes budget:</p>
 * <ul>
 *   <li>{@link Verdict#CAPTURE} - the value fits both its per-key cap and the
 *       remaining total budget; record it.</li>
 *   <li>{@link Verdict#SKIP_OVERSIZE} - the value exceeds the per-key cap; skip just
 *       this one, keep going.</li>
 *   <li>{@link Verdict#ABORT_SNAPSHOT_TOO_LARGE} - recording this value would blow the
 *       total budget; refuse the whole snapshot. A silent partial PDC snapshot is a
 *       support nightmare, so this is a hard error.</li>
 * </ul>
 */
public final class PdcCaptureLimiter {

    /** What to do with one offered value. */
    public enum Verdict {
        CAPTURE,
        SKIP_OVERSIZE,
        ABORT_SNAPSHOT_TOO_LARGE
    }

    private final long perKeyMax;
    private final long totalMax;
    private long totalBytes;
    private int captured;

    public PdcCaptureLimiter(long perKeyMax, long totalMax) {
        if (perKeyMax < 1) {
            throw new IllegalArgumentException("perKeyMax must be >= 1, got " + perKeyMax);
        }
        if (totalMax < 1) {
            throw new IllegalArgumentException("totalMax must be >= 1, got " + totalMax);
        }
        this.perKeyMax = perKeyMax;
        this.totalMax = totalMax;
    }

    /** A limiter with the production defaults from {@link PdcLimits}. */
    public static PdcCaptureLimiter defaults() {
        return new PdcCaptureLimiter(PdcLimits.PER_KEY_BYTES, PdcLimits.TOTAL_BYTES);
    }

    /**
     * Offers one value's footprint to the budget.
     *
     * @param valueBytes decoded size of the value, &ge; 0
     * @return the verdict, never null
     */
    public Verdict offer(long valueBytes) {
        if (valueBytes < 0) {
            throw new IllegalArgumentException("valueBytes must be >= 0, got " + valueBytes);
        }
        if (valueBytes > perKeyMax) {
            return Verdict.SKIP_OVERSIZE;
        }
        if (totalBytes + valueBytes > totalMax) {
            return Verdict.ABORT_SNAPSHOT_TOO_LARGE;
        }
        totalBytes += valueBytes;
        captured++;
        return Verdict.CAPTURE;
    }

    /** @return total bytes accepted so far */
    public long totalBytes() {
        return totalBytes;
    }

    /** @return remaining total budget in bytes */
    public long remainingBytes() {
        return totalMax - totalBytes;
    }

    /** @return number of values accepted so far */
    public int capturedCount() {
        return captured;
    }

    /** @return the configured per-key cap */
    public long perKeyMax() {
        return perKeyMax;
    }

    /** @return the configured total cap */
    public long totalMax() {
        return totalMax;
    }
}
