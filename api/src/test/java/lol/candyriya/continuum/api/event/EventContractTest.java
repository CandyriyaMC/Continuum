package lol.candyriya.continuum.api.event;

import lol.candyriya.continuum.api.report.ModuleCodes;
import lol.candyriya.continuum.api.report.ModuleResult;
import lol.candyriya.continuum.api.session.SessionInfo;
import lol.candyriya.continuum.api.session.SessionState;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EventContractTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void loadEventIsCancellableAndSticky() {
        ContinuumLoadEvent event = new ContinuumLoadEvent(PLAYER);

        assertFalse(event.isCancelled());
        event.cancel("maintenance window");
        assertTrue(event.isCancelled());
        assertEquals("maintenance window", event.cancelReason());

        // cancelling without a reason is legal
        ContinuumLoadEvent bare = new ContinuumLoadEvent(PLAYER);
        bare.cancel(null);
        assertTrue(bare.isCancelled());
        assertNull(bare.cancelReason());
    }

    @Test
    void saveEventCarriesTheReason() {
        ContinuumSaveEvent event = new ContinuumSaveEvent(PLAYER, SnapshotReason.TRANSFER);

        assertEquals(SnapshotReason.TRANSFER, event.reason());
        assertEquals(PLAYER, event.playerId());
        assertFalse(event.isCancelled());
    }

    @Test
    void transferEventAllowsUnknownDestination() {
        ContinuumTransferEvent event = new ContinuumTransferEvent(PLAYER, "hub-1", null);

        assertEquals("hub-1", event.fromBackendId());
        assertNull(event.toBackendId());
        event.cancel("proxy: maintenance");
        assertTrue(event.isCancelled());
        assertEquals("proxy: maintenance", event.cancelReason());
    }

    @Test
    void transferEventRejectsBlankSource() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuumTransferEvent(PLAYER, "", "hub-2"));
        assertThrows(NullPointerException.class, () -> new ContinuumTransferEvent(PLAYER, null, "hub-2"));
    }

    @Test
    void moduleEventsCarryTheRow() {
        ModuleResult row = ModuleResult.ok("continuum.inventory", 12);

        ContinuumModuleCaptureEvent capture = new ContinuumModuleCaptureEvent(PLAYER, row);
        ContinuumModuleRestoreEvent restore = new ContinuumModuleRestoreEvent(PLAYER, row);

        assertEquals("continuum.inventory", capture.result().moduleId());
        assertEquals("continuum.inventory", restore.result().moduleId());
        assertEquals(ModuleCodes.OK, capture.result().code());
    }

    @Test
    void sessionEventsExposePlayerAndSession() {
        SessionInfo session = new SessionInfo(PLAYER, "survival-1", SessionState.ACTIVE, 9, Instant.now());

        assertEquals(PLAYER, new ContinuumSessionCreatedEvent(session).playerId());
        assertEquals(PLAYER, new ContinuumSessionReleasedEvent(session).playerId());
        assertEquals(PLAYER, new ContinuumRecoveryEvent(session).playerId());
        assertEquals(SessionState.ACTIVE, new ContinuumSessionCreatedEvent(session).session().state());
    }

    @Test
    void failureEventAllowsGlobalFailures() {
        ContinuumFailureEvent global = new ContinuumFailureEvent(null, null, "STORAGE_UNAVAILABLE", "database is down");
        ContinuumFailureEvent moduleScoped = new ContinuumFailureEvent(PLAYER, "continuum.vitals", ModuleCodes.TIMEOUT, "timed out");

        assertNull(global.playerId());
        assertNull(global.moduleId());
        assertEquals(PLAYER, moduleScoped.playerId());
        assertEquals("continuum.vitals", moduleScoped.moduleId());
    }

    @Test
    void failureEventValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuumFailureEvent(PLAYER, null, "", "msg"));
        assertThrows(IllegalArgumentException.class, () -> new ContinuumFailureEvent(PLAYER, null, "CODE", "  "));
        assertThrows(lol.candyriya.continuum.api.module.InvalidModuleIdException.class,
                () -> new ContinuumFailureEvent(PLAYER, "Bad Id", "CODE", "msg"));
        assertThrows(NullPointerException.class, () -> new ContinuumFailureEvent(PLAYER, null, null, "msg"));
        assertThrows(NullPointerException.class, () -> new ContinuumFailureEvent(PLAYER, null, "CODE", null));
    }

    @Test
    void restoreEventCarriesTheReport() {
        var report = new lol.candyriya.continuum.api.report.RestoreReport(
                PLAYER, 3, lol.candyriya.continuum.api.snapshot.SyncResult.FAILED,
                java.util.List.of(), Instant.now());

        ContinuumRestoreEvent event = new ContinuumRestoreEvent(PLAYER, report);

        assertEquals(PLAYER, event.playerId());
        assertEquals(3, event.report().snapshotId());
    }
}
