package lol.candyriya.continuum.api.session;

import org.jetbrains.annotations.ApiStatus;

/**
 * Lifecycle states of a player session in Continuum.
 *
 * <p>A session is the exclusive ownership record of a player's synchronized state on one
 * backend at a time, fenced by a {@code fencingToken} checked inside the atomic database
 * write. Only the backend holding the current token may write snapshots for the player;
 * any backend holding a stale token is rejected by the database itself, not by a check in
 * memory.</p>
 *
 * <p>States:</p>
 * <ul>
 *   <li>{@link #CONNECTING} — the player is about to join; ownership is being assigned.</li>
 *   <li>{@link #LOADING} — state is being restored; the player is not interactive yet.</li>
 *   <li>{@link #ACTIVE} — the player is online and owned by this backend.</li>
 *   <li>{@link #TRANSFERRING} — ownership is handing off to another backend.</li>
 *   <li>{@link #SAVING} — a final snapshot is being written before release.</li>
 *   <li>{@link #RELEASED} — the session ended; the record is kept for bookkeeping only.</li>
 *   <li>{@link #RECOVERY_REQUIRED} — the previous backend died mid-flight; a human or the
 *       recovery flow must decide what to do with the possibly-stale state.</li>
 * </ul>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public enum SessionState {

    /** Join accepted, ownership being assigned. */
    CONNECTING,

    /** State is being restored; the player is not interactive. */
    LOADING,

    /** Online and owned. */
    ACTIVE,

    /** Hand-off to another backend in progress. */
    TRANSFERRING,

    /** Final snapshot in progress before release. */
    SAVING,

    /** Ended; kept for history. */
    RELEASED,

    /** Previous owner died mid-flight; needs explicit recovery. */
    RECOVERY_REQUIRED
}
