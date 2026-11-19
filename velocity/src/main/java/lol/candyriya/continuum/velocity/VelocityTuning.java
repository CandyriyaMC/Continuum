package lol.candyriya.continuum.velocity;

/**
 * Tuning for the {@code velocity:} block of {@code velocity.yml}. Values are
 * range-checked by {@link VelocityLocalConfigLoader}, which is also the only
 * thing that builds this record.
 *
 * @param reconnectDebounceSeconds seconds after a player's last transition for which the
 *                                 next one is still the same episode. Rapid reconnects inside
 *                                 the window are a reconnect storm and their loss-reconciliation
 *                                 signals are debounced. Must be within 1-600.
 */
public record VelocityTuning(long reconnectDebounceSeconds) {

    /** Used when the block or key is missing entirely. */
    public static final VelocityTuning DEFAULT = new VelocityTuning(10);

    public static final long MIN_DEBOUNCE_SECONDS = 1;
    public static final long MAX_DEBOUNCE_SECONDS = 600;
}
