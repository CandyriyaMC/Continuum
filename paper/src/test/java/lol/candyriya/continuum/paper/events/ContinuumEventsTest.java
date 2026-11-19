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
import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.report.ModuleResult;
import lol.candyriya.continuum.api.report.RestoreReport;
import lol.candyriya.continuum.api.session.SessionInfo;
import lol.candyriya.continuum.api.session.SessionState;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuumEventsTest {

    private final PaperEventBus bus = new PaperEventBus();
    private final UUID playerId = UUID.randomUUID();

    private SessionInfo session(SessionState state) {
        return new SessionInfo(playerId, "survival-1", state, 42L, Instant.now());
    }

    private RestoreReport report() {
        return new RestoreReport(playerId, 42L, SyncResult.SUCCESS,
                List.of(ModuleResult.ok(BuiltinModules.INVENTORY, 7)), Instant.now());
    }

    @Test
    void loadBuildsAndFiresEvent() {
        AtomicReference<ContinuumEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumLoadEvent.class, captured::set);

        ContinuumLoadEvent event = ContinuumEvents.load(bus, playerId);

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertFalse(event.isCancelled());
    }

    @Test
    void saveBuildsAndFiresEvent() {
        AtomicReference<ContinuumSaveEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumSaveEvent.class, captured::set);

        ContinuumSaveEvent event = ContinuumEvents.save(bus, playerId, SnapshotReason.AUTOSAVE);

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals(SnapshotReason.AUTOSAVE, event.reason());
    }

    @Test
    void restoreBuildsAndFiresEvent() {
        RestoreReport report = report();
        AtomicReference<ContinuumRestoreEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumRestoreEvent.class, captured::set);

        ContinuumRestoreEvent event = ContinuumEvents.restore(bus, playerId, report);

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals(report, event.report());
    }

    @Test
    void transferBuildsAndFiresEvent() {
        AtomicReference<ContinuumTransferEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumTransferEvent.class, captured::set);

        ContinuumTransferEvent event = ContinuumEvents.transfer(bus, playerId, "survival-1", "lobby");

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals("survival-1", event.fromBackendId());
        assertEquals("lobby", event.toBackendId());
    }

    @Test
    void transferAllowsUnknownDestination() {
        ContinuumTransferEvent event = ContinuumEvents.transfer(bus, playerId, "survival-1", null);

        assertEquals("survival-1", event.fromBackendId());
        assertNull(event.toBackendId());
    }

    @Test
    void moduleCaptureBuildsAndFiresEvent() {
        ModuleResult result = ModuleResult.ok(BuiltinModules.INVENTORY, 12);
        AtomicReference<ContinuumModuleCaptureEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumModuleCaptureEvent.class, captured::set);

        ContinuumModuleCaptureEvent event = ContinuumEvents.moduleCapture(bus, playerId, result);

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals(result, event.result());
    }

    @Test
    void moduleRestoreBuildsAndFiresEvent() {
        ModuleResult result = ModuleResult.failed(BuiltinModules.VITALS, "RESTORE_FAILED", "executor died", 3);
        AtomicReference<ContinuumModuleRestoreEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumModuleRestoreEvent.class, captured::set);

        ContinuumModuleRestoreEvent event = ContinuumEvents.moduleRestore(bus, playerId, result);

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals(result, event.result());
    }

    @Test
    void sessionCreatedBuildsAndFiresEvent() {
        SessionInfo session = session(SessionState.ACTIVE);
        AtomicReference<ContinuumSessionCreatedEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumSessionCreatedEvent.class, captured::set);

        ContinuumSessionCreatedEvent event = ContinuumEvents.sessionCreated(bus, session);

        assertSame(event, captured.get());
        assertEquals(session, event.session());
        assertEquals(session.playerId(), event.playerId());
    }

    @Test
    void sessionReleasedBuildsAndFiresEvent() {
        SessionInfo session = session(SessionState.RELEASED);
        AtomicReference<ContinuumSessionReleasedEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumSessionReleasedEvent.class, captured::set);

        ContinuumSessionReleasedEvent event = ContinuumEvents.sessionReleased(bus, session);

        assertSame(event, captured.get());
        assertEquals(session, event.session());
        assertEquals(session.playerId(), event.playerId());
    }

    @Test
    void recoveryBuildsAndFiresEvent() {
        SessionInfo session = session(SessionState.RECOVERY_REQUIRED);
        AtomicReference<ContinuumRecoveryEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumRecoveryEvent.class, captured::set);

        ContinuumRecoveryEvent event = ContinuumEvents.recovery(bus, session);

        assertSame(event, captured.get());
        assertEquals(session, event.session());
        assertEquals(SessionState.RECOVERY_REQUIRED, event.session().state());
    }

    @Test
    void failureBuildsAndFiresEvent() {
        AtomicReference<ContinuumFailureEvent> captured = new AtomicReference<>();
        bus.subscribe(ContinuumFailureEvent.class, captured::set);

        ContinuumFailureEvent event = ContinuumEvents.failure(
                bus, playerId, BuiltinModules.INVENTORY, "CAPTURE_FAILED", "storage write failed");

        assertSame(event, captured.get());
        assertEquals(playerId, event.playerId());
        assertEquals(BuiltinModules.INVENTORY, event.moduleId());
        assertEquals("CAPTURE_FAILED", event.code());
        assertEquals("storage write failed", event.message());
    }

    @Test
    void failureSupportsGlobalOutageWithoutPlayerOrModule() {
        ContinuumFailureEvent event = ContinuumEvents.failure(
                bus, null, null, "STORAGE_UNAVAILABLE", "postgres pool exhausted");

        assertNull(event.playerId());
        assertNull(event.moduleId());
        assertEquals("STORAGE_UNAVAILABLE", event.code());
    }

    @Test
    void vetoIntoFacadeIsVisibleOnTheReturnedEvent() {
        bus.subscribe(ContinuumSaveEvent.class, e -> e.cancel("maintenance mode"));

        ContinuumSaveEvent event = ContinuumEvents.save(bus, playerId, SnapshotReason.SHUTDOWN);

        // the caller is expected to check this and skip the capture
        assertTrue(event.isCancelled());
        assertEquals("maintenance mode", event.cancelReason());
    }

    @Test
    void nullBusIsRejected() {
        assertThrows(NullPointerException.class, () -> ContinuumEvents.load(null, playerId));
        assertThrows(NullPointerException.class, () -> ContinuumEvents.moduleCapture(null, playerId,
                ModuleResult.ok(BuiltinModules.INVENTORY, 1)));
    }

    @Test
    void blankCodeIsRejectedByTheEventContract() {
        assertThrows(IllegalArgumentException.class,
                () -> ContinuumEvents.failure(bus, playerId, null, "  ", "message"));
    }

    @Test
    void firedEventsCarryUniqueIdentityPerCall() {
        ContinuumLoadEvent first = ContinuumEvents.load(bus, playerId);
        ContinuumLoadEvent second = ContinuumEvents.load(bus, playerId);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "each call fires a fresh event instance");
    }
}
