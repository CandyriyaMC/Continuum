package lol.candyriya.continuum.api.snapshot;

import org.jetbrains.annotations.ApiStatus;

/**
 * The overall verdict of a capture or restore operation, derived from the per-module
 * results.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — every enabled module reported {@code OK} (or {@code WARN}).</li>
 *   <li>{@link #PARTIAL} — at least one optional module failed or was skipped for a
 *       dependency reason, but every required module succeeded. The operation produced a
 *       usable (capture) or applied (restore) result; some state may be stale.</li>
 *   <li>{@link #FAILED} — a required module failed. For a capture, no snapshot was
 *       persisted (or it was persisted only for diagnostics and must not be used as the
 *       basis for restore). For a restore, the player's state is <em>not</em> what the
 *       snapshot contains — the caller must route the player into the recovery flow
 *       rather than hand them a half-loaded character.</li>
 * </ul>
 *
 * <p>{@code PARTIAL} and {@code SUCCESS} both mean the operation finished its unit of
 * work; {@code FAILED} means it did not and the caller owns the follow-up.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public enum SyncResult {

    /** Everything that was supposed to work, worked. */
    SUCCESS,

    /** Required modules worked; at least one optional module did not. */
    PARTIAL,

    /** A required module failed; the operation did not achieve its goal. */
    FAILED
}
