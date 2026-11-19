package lol.candyriya.continuum.paper.modules.location;

import org.jetbrains.annotations.Nullable;

/**
 * Captured location state; the unit of data the location module persists and restores.
 *
 * <p>Wire format (stable, versioned; bump {@link #FORMAT} on an incompatible change):
 * the payload is a JSON object with snake_case keys under Continuum's snapshot mapper.</p>
 *
 * <pre>{@code
 * {
 *   "format": 1,
 *   "captured_at_epoch_milli": 1763456789012,
 *   "position": { "world": "survival", "environment": "NORMAL",
 *                 "x": 123.5, "y": 64.0, "z": -877.25, "yaw": 90.0, "pitch": 0.0 },
 *   "respawn_point": { "world": "survival", "environment": "NORMAL",
 *                      "x": 100.0, "y": 65.0, "z": -900.0, "yaw": 0.0, "pitch": 0.0 },
 *   "last_death_point": null
 * }
 * }</pre>
 *
 * <p>{@code format} is the payload schema marker; {@link #FORMAT} is the only value this
 * build writes and the only one it restores. {@code captured_at_epoch_milli} is the capture
 * wall-clock time in epoch milliseconds — kept for diagnostics, no time-based pruning is
 * applied at restore (a position cannot "expire").</p>
 *
 * <p>{@link #respawnPoint} covers both the bed respawn and the respawn anchor; the platform
 * exposes a single respawn-location API for both, so there is nothing anchor-specific to
 * store. It is {@code null} when the player has no respawn point (never slept, anchor never
 * charged, or the data was unreadable at capture — capture logs a warning in the latter
 * case). {@link #lastDeathPoint} is {@code null} when the player never died on record.</p>
 *
 * <p>{@link #position} is mandatory for a usable payload: Continuum only ever receives it
 * from {@link LocationModule#capture}, which fails the capture rather than write a payload
 * without one. This record's canonical constructor is deliberately lenient (no exceptions):
 * a payload decoded from JSON without its position is rejected by {@code LocationValidation}
 * instead of blowing up the codec, so restore treats it as a clean skip. A structurally
 * corrupt {@link PositionSnapshot} nested inside the payload may still throw at decode time
 * (a codec-level failure) — that is the corruption case, not the missing-data case, and it
 * aborts the snapshot read with a clear error before any restore runs. Unknown JSON
 * properties are tolerated on read.</p>
 *
 * @param format              payload format version; always {@value #FORMAT} in this build
 * @param capturedAtEpochMilli wall-clock time of capture, epoch milliseconds
 * @param position            the player's position at capture; mandatory at the platform level
 * @param respawnPoint        the stored respawn point (bed or respawn anchor), or null
 * @param lastDeathPoint      the last death point, or null
 */
public record LocationPayload(
        int format,
        long capturedAtEpochMilli,
        @Nullable PositionSnapshot position,
        @Nullable PositionSnapshot respawnPoint,
        @Nullable PositionSnapshot lastDeathPoint) {

    /** Wire format version written and restored by this build. */
    public static final int FORMAT = 1;

    /**
     * Canonical constructor, deliberately lenient: this record is also the Jackson decode
     * target, and an exception here would turn a corrupt snapshot into a codec failure
     * instead of a validation failure. All real checks live in {@code LocationValidation}.
     */
    public LocationPayload {
    }
}
