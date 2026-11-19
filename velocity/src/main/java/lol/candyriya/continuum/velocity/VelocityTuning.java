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
 * @param handoffRestoreTimeoutSeconds how long the proxy waits for the target backend to report
 *                                 the player restored and resumed before it gives up on an
 *                                 in-flight handoff. Pre-restore phases also use this as the
 *                                 staleness bound. Must be within 1-120, default {@value #DEFAULT_RESTORE_TIMEOUT_SECONDS}.
 * @param captureTimeoutSeconds   how long the proxy waits for the backend to answer a
 *                                 {@code continuum.capture.request} (fired before a vanilla
 *                                 server switch) before refusing the switch. Must be within
 *                                 1-10, default {@value #DEFAULT_CAPTURE_TIMEOUT_SECONDS}.
 */
public record VelocityTuning(long reconnectDebounceSeconds, long handoffRestoreTimeoutSeconds, long captureTimeoutSeconds) {

    /** Used when the block or key is missing entirely. */
    public static final VelocityTuning DEFAULT = new VelocityTuning(10, 8, 3);

    public static final long MIN_DEBOUNCE_SECONDS = 1;
    public static final long MAX_DEBOUNCE_SECONDS = 600;

    /** Default for {@code velocity.handoff-restore-timeout-seconds}. */
    public static final long DEFAULT_RESTORE_TIMEOUT_SECONDS = 8;

    public static final long MIN_RESTORE_TIMEOUT_SECONDS = 1;
    public static final long MAX_RESTORE_TIMEOUT_SECONDS = 120;

    /** Default for {@code velocity.capture-timeout-seconds}. */
    public static final long DEFAULT_CAPTURE_TIMEOUT_SECONDS = 3;

    public static final long MIN_CAPTURE_TIMEOUT_SECONDS = 1;
    public static final long MAX_CAPTURE_TIMEOUT_SECONDS = 10;
}
