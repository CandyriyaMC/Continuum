package lol.candyriya.continuum.paper.events;

import lol.candyriya.continuum.api.event.ContinuumEvent;
import lol.candyriya.continuum.api.event.ContinuumFailureEvent;
import lol.candyriya.continuum.api.event.ContinuumLoadEvent;
import lol.candyriya.continuum.api.event.ContinuumModuleCaptureEvent;
import lol.candyriya.continuum.api.event.ContinuumModuleRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumRecoveryEvent;
import lol.candyriya.continuum.api.event.ContinuumRestoreEvent;
import lol.candyriya.continuum.api.event.ContinuumSaveEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionCreatedEvent;
import lol.candyriya.continuum.api.event.ContinuumSessionReleasedEvent;
import lol.candyriya.continuum.api.event.ContinuumTransferEvent;
import lol.candyriya.continuum.api.report.ModuleResult;
import lol.candyriya.continuum.api.report.RestoreReport;
import lol.candyriya.continuum.api.session.SessionInfo;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The single place first-party Continuum code builds and fires events. Constructs the exact
 * api event types, hands them to the platform bus and returns the fired instance so the
 * caller can inspect it.
 *
 * <p>Every method here does three things in one call: validate the arguments (the event
 * constructors do that), dispatch through {@link PaperEventBus#dispatch(ContinuumEvent)} —
 * which already isolates listener failures — and return the event. Listener exceptions never
 * reach the caller; argument validation failures are programmer errors and do throw.</p>
 *
 * <p><b>Cancellable events.</b> {@link #load}, {@link #save} and {@link #transfer} return
 * the fired event; the caller MUST check {@code isCancelled()} once the call returns and
 * honor the veto — abort the restore and let the player join with default state, skip the
 * snapshot, refuse the transfer. A vetoed operation must not proceed; there is no other
 * signal.</p>
 *
 * <p><b>Example.</b></p>
 * <pre>{@code
 * ContinuumSaveEvent event = ContinuumEvents.save(bus, player.getUniqueId(), SnapshotReason.AUTOSAVE);
 * if (event.isCancelled()) {
 *     // snapshot skipped; nothing to do, maybe a debug line with event.cancelReason()
 *     return;
 * }
 * // ... capture and persist ...
 * }</pre>
 *
 * <p>This class ships in both platform modules; the copies are identical apart from the bus
 * type they accept. Keep them in sync.</p>
 *
 * @since 1.0.0
 */
public final class ContinuumEvents {

    private ContinuumEvents() {
    }

    /**
     * Fires {@link ContinuumLoadEvent} — call before a restore begins. Cancellable: honor
     * the veto by aborting the restore (the player joins with default state; log a warning
     * with {@link ContinuumLoadEvent#cancelReason()}).
     *
     * @param bus      the platform event bus, never null
     * @param playerId the player being restored, never null
     * @return the fired event; check {@code isCancelled()} before restoring
     * @throws NullPointerException when {@code bus} or {@code playerId} is null
     */
    public static @NotNull ContinuumLoadEvent load(@NotNull PaperEventBus bus, @NotNull UUID playerId) {
        Objects.requireNonNull(bus, "bus");
        ContinuumLoadEvent event = new ContinuumLoadEvent(playerId);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumSaveEvent} — call before a capture begins. Cancellable: honor
     * the veto by skipping this capture entirely (no snapshot is written; log a debug line
     * with {@link ContinuumSaveEvent#cancelReason()}).
     *
     * @param bus      the platform event bus, never null
     * @param playerId the player being captured, never null
     * @param reason   why the capture is happening, never null
     * @return the fired event; check {@code isCancelled()} before capturing
     * @throws NullPointerException when {@code bus}, {@code playerId} or {@code reason} is null
     */
    public static @NotNull ContinuumSaveEvent save(@NotNull PaperEventBus bus, @NotNull UUID playerId,
                                                   @NotNull SnapshotReason reason) {
        Objects.requireNonNull(bus, "bus");
        ContinuumSaveEvent event = new ContinuumSaveEvent(playerId, reason);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumRestoreEvent} — call after a restore finishes, exactly once per
     * restore, with the full report attached regardless of outcome. Not cancellable.
     *
     * @param bus      the platform event bus, never null
     * @param playerId the player that was restored, never null
     * @param report   the full per-module outcome, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus}, {@code playerId} or {@code report} is null
     */
    public static @NotNull ContinuumRestoreEvent restore(@NotNull PaperEventBus bus, @NotNull UUID playerId,
                                                         @NotNull RestoreReport report) {
        Objects.requireNonNull(bus, "bus");
        ContinuumRestoreEvent event = new ContinuumRestoreEvent(playerId, report);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumTransferEvent} — call when a backend-to-backend transfer is
     * initiated, before any backend is told to release or restore anything. Cancellable:
     * honor the veto by refusing the transfer (the player stays put; log with
     * {@link ContinuumTransferEvent#cancelReason()}).
     *
     * @param bus           the platform event bus, never null
     * @param playerId      the player being transferred, never null
     * @param fromBackendId the backend the player is on, never null, never blank
     * @param toBackendId   the intended destination, or null when not yet decided
     * @return the fired event; check {@code isCancelled()} before transferring
     * @throws NullPointerException     when {@code bus}, {@code playerId} or {@code fromBackendId} is null
     * @throws IllegalArgumentException when {@code fromBackendId} is blank
     */
    public static @NotNull ContinuumTransferEvent transfer(@NotNull PaperEventBus bus, @NotNull UUID playerId,
                                                           @NotNull String fromBackendId,
                                                           @Nullable String toBackendId) {
        Objects.requireNonNull(bus, "bus");
        ContinuumTransferEvent event = new ContinuumTransferEvent(playerId, fromBackendId, toBackendId);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumModuleCaptureEvent} — call once per module after its capture
     * completes (success, failure or skip). Not cancellable.
     *
     * @param bus      the platform event bus, never null
     * @param playerId the player that was captured, never null
     * @param result   the module's outcome row, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus}, {@code playerId} or {@code result} is null
     */
    public static @NotNull ContinuumModuleCaptureEvent moduleCapture(@NotNull PaperEventBus bus,
                                                                     @NotNull UUID playerId,
                                                                     @NotNull ModuleResult result) {
        Objects.requireNonNull(bus, "bus");
        ContinuumModuleCaptureEvent event = new ContinuumModuleCaptureEvent(playerId, result);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumModuleRestoreEvent} — call once per module after its restore
     * completes (success, failure or skip). Not cancellable.
     *
     * @param bus      the platform event bus, never null
     * @param playerId the player that was restored, never null
     * @param result   the module's outcome row, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus}, {@code playerId} or {@code result} is null
     */
    public static @NotNull ContinuumModuleRestoreEvent moduleRestore(@NotNull PaperEventBus bus,
                                                                     @NotNull UUID playerId,
                                                                     @NotNull ModuleResult result) {
        Objects.requireNonNull(bus, "bus");
        ContinuumModuleRestoreEvent event = new ContinuumModuleRestoreEvent(playerId, result);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumSessionCreatedEvent} — call once per session, after the fencing
     * token is reserved. Not cancellable.
     *
     * @param bus     the platform event bus, never null
     * @param session the newly created session, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus} or {@code session} is null
     */
    public static @NotNull ContinuumSessionCreatedEvent sessionCreated(@NotNull PaperEventBus bus,
                                                                       @NotNull SessionInfo session) {
        Objects.requireNonNull(bus, "bus");
        ContinuumSessionCreatedEvent event = new ContinuumSessionCreatedEvent(session);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumSessionReleasedEvent} — call once per session, after the final
     * fencing-token check. The carried session has state {@code RELEASED}. Not cancellable.
     *
     * @param bus     the platform event bus, never null
     * @param session the released session, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus} or {@code session} is null
     */
    public static @NotNull ContinuumSessionReleasedEvent sessionReleased(@NotNull PaperEventBus bus,
                                                                         @NotNull SessionInfo session) {
        Objects.requireNonNull(bus, "bus");
        ContinuumSessionReleasedEvent event = new ContinuumSessionReleasedEvent(session);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumRecoveryEvent} — call when a backend died holding a player's
     * session. Listeners should alert operators; there is nothing to cancel.
     *
     * @param bus     the platform event bus, never null
     * @param session the session stuck in {@code RECOVERY_REQUIRED}, never null
     * @return the fired event
     * @throws NullPointerException when {@code bus} or {@code session} is null
     */
    public static @NotNull ContinuumRecoveryEvent recovery(@NotNull PaperEventBus bus,
                                                           @NotNull SessionInfo session) {
        Objects.requireNonNull(bus, "bus");
        ContinuumRecoveryEvent event = new ContinuumRecoveryEvent(session);
        bus.dispatch(event);
        return event;
    }

    /**
     * Fires {@link ContinuumFailureEvent} — informational; there is nothing to cancel and
     * no guaranteed follow-up. {@code playerId} may be null for global failures (a storage
     * outage, a coordinator problem); {@code moduleId} may be null when the failure is not
     * attributable to one module.
     *
     * @param bus      the platform event bus, never null
     * @param playerId the affected player, or null for global failures
     * @param moduleId the affected module, or null when not attributable to one
     * @param code     the stable machine-readable failure code, never null, never blank
     * @param message  an English explanation suitable for logs, never null, never blank
     * @return the fired event
     * @throws NullPointerException     when {@code bus}, {@code code} or {@code message} is null
     * @throws IllegalArgumentException when {@code code} or {@code message} is blank, or the
     *         module id is not well-formed
     */
    public static @NotNull ContinuumFailureEvent failure(@NotNull PaperEventBus bus,
                                                         @Nullable UUID playerId,
                                                         @Nullable String moduleId,
                                                         @NotNull String code,
                                                         @NotNull String message) {
        Objects.requireNonNull(bus, "bus");
        ContinuumFailureEvent event = new ContinuumFailureEvent(playerId, moduleId, code, message);
        bus.dispatch(event);
        return event;
    }
}
