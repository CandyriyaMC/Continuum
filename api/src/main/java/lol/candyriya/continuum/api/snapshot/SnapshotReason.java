package lol.candyriya.continuum.api.snapshot;

import org.jetbrains.annotations.ApiStatus;

/**
 * Why a snapshot was taken. Persisted with every snapshot, surfaced in history and the
 * admin commands; part of the stored schema, so the values are permanent.
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
public enum SnapshotReason {

    /** Periodic autosave while the player is online. */
    AUTOSAVE,

    /** Captured during a backend-to-backend transfer, before the hand-off. */
    TRANSFER,

    /** Captured when the player leaves the backend (quit or kick). */
    DISCONNECT,

    /** Captured on backend shutdown. */
    SHUTDOWN,

    /** Captured on an explicit admin command or console action. */
    MANUAL,

    /** Captured on request through the public API. */
    API,

    /** Captured as part of crash recovery, to pin down the state seen after a failure. */
    RECOVERY,

    /** A rollback produced this snapshot — a verified copy of an older one under a new id. */
    ROLLBACK
}
