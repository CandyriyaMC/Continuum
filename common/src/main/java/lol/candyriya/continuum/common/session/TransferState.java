package lol.candyriya.continuum.common.session;

/**
 * Lifecycle of a player transfer between backends.
 *
 * <p>Ordered roughly by flow, but consumers must not rely on ordinal(): the
 * state machine is defined by storage-layer transitions guarded by fencing
 * tokens. Pure data.</p>
 */
public enum TransferState {

    /** Transfer record created, nothing persisted yet. */
    CREATED,

    /** Backend A is capturing the pre-transfer snapshot. */
    CAPTURING,

    /** Snapshot durably stored, safe to proceed with the handoff. */
    SNAPSHOT_SAVED,

    /** Handoff event requested (outbox/RabbitMQ/plugin messaging). */
    HANDOFF_REQUESTED,

    /** Velocity is connecting the player to the target backend. */
    CONNECTING,

    /** Target backend is restoring the snapshot; player is gate-blocked. */
    RESTORING,

    /** Target backend restored successfully and reports the player active. */
    ACTIVE,

    /** Final commit: target confirmed ownership, source released. Terminal. */
    COMMITTED,

    /** Transfer failed before commit. Terminal; player stays on the source if possible. */
    FAILED,

    /** Something half-finished could not be resolved automatically. */
    RECOVERY_REQUIRED
}
