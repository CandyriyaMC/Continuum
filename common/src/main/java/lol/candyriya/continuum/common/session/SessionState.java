package lol.candyriya.continuum.common.session;

/**
 * Lifecycle of a player session owned by a Continuum backend.
 *
 * <p>Pure data: transitions live in the storage layer (they are guarded by
 * fencing tokens inside atomic database operations), not in this enum.</p>
 */
public enum SessionState {

    /** Connection accepted, restore has not started yet. */
    CONNECTING,

    /** Snapshot is being loaded and modules are being restored. */
    LOADING,

    /** Player is fully restored and owns an active session. */
    ACTIVE,

    /** A transfer to another backend is in flight; writes are coordinated by the transfer. */
    TRANSFERRING,

    /** A snapshot is being captured/persisted (autosave, disconnect, transfer). */
    SAVING,

    /** Session was ended cleanly and ownership was released. Terminal. */
    RELEASED,

    /** Restore failed in a way that needs operator attention before play resumes. */
    RECOVERY_REQUIRED
}
