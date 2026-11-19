package lol.candyriya.continuum.messaging.events;

import lol.candyriya.continuum.common.protocol.MessageTypeRegistry;
import lol.candyriya.continuum.common.protocol.payload.CoreEventTypes;

/**
 * The event-backbone registry: every event type the network speaks, in one
 * place. Core transfer-flow types come from {@link CoreEventTypes}; the
 * backbone types below are layered on top, same dotted-lowercase convention.
 *
 * <p>Register once at bootstrap. The registry is strict - consumers refuse
 * unregistered types, which is what makes a typo'd or spoofed frame fail
 * closed instead of landing in a wildcard handler.</p>
 */
public final class BackboneEvents {

    /** Session claimed on a backend; payload {@code SessionCreatedPayload}. */
    public static final String SESSION_CREATED = "continuum.session.created";

    /** Session ownership moved between backends; payload {@code SessionTransferredPayload}. */
    public static final String SESSION_TRANSFERRED = "continuum.session.transferred";

    /** Snapshot committed to the database; payload {@code SnapshotUpdatedPayload}. */
    public static final String SNAPSHOT_UPDATED = "continuum.snapshot.updated";

    /** Backend health heartbeat/transition; payload {@code BackendHealthPayload}. */
    public static final String BACKEND_HEALTH = "continuum.backend.health";

    /** A session needs operator or automatic recovery; payload {@code RecoveryRequiredPayload}. */
    public static final String RECOVERY_REQUIRED = "continuum.recovery.required";

    private BackboneEvents() {
    }

    /** A registry pre-loaded with the core flow types and every backbone type. */
    public static MessageTypeRegistry create() {
        return CoreEventTypes.create()
                .register(SESSION_CREATED, SessionCreatedPayload.class)
                .register(SESSION_TRANSFERRED, SessionTransferredPayload.class)
                .register(SNAPSHOT_UPDATED, SnapshotUpdatedPayload.class)
                .register(BACKEND_HEALTH, BackendHealthPayload.class)
                .register(RECOVERY_REQUIRED, RecoveryRequiredPayload.class);
    }
}
