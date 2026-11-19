package lol.candyriya.continuum.paper.modules.inventory;

/**
 * Payload format gate and the future migration hook.
 *
 * <p>Format {@code 1} is the only format this build understands. A stored payload with any
 * other format is rejected loudly — never silently truncated, never "best effort" decoded.
 * Loading a newer snapshot on an older server must fail, not corrupt.</p>
 *
 * <p>When format 2 ever lands, migrations live here as an ordered chain (raw format N payload
 * in, format N+1 payload out), mirroring how the envelope layer migrates snapshots. Until
 * then there is nothing to step through, and guessing would be worse than refusing.</p>
 */
final class PayloadMigrations {

    /** The one and only supported payload format. */
    static final int CURRENT_FORMAT = 1;

    private PayloadMigrations() {
        // static access only
    }

    /**
     * Accepts the payload only when its format is one this build can interpret.
     *
     * @return the format, always {@link #CURRENT_FORMAT}
     * @throws InventoryStateException for any other (stale, future or corrupt) format
     */
    static int requireSupported(int format) {
        if (format != CURRENT_FORMAT) {
            throw new InventoryStateException("unsupported payload format " + format
                    + " (this build understands format " + CURRENT_FORMAT + "); "
                    + "if this snapshot was written by a newer Continuum, upgrade the server");
        }
        return format;
    }
}
