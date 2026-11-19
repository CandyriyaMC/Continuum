package lol.candyriya.continuum.paper.events;

import lol.candyriya.continuum.api.event.CancellableContinuumEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperEventBusTest {

    private final PaperEventBus bus = new PaperEventBus();
    private final UUID playerId = UUID.randomUUID();

    private SessionInfo session(SessionState state) {
        return new SessionInfo(playerId, "survival-1", state, 42L, Instant.now());
    }

    private RestoreReport report() {
        return new RestoreReport(playerId, 42L, SyncResult.SUCCESS,
                List.of(ModuleResult.ok(BuiltinModules.INVENTORY, 7)), Instant.now());
    }

    // ---- routing ----------------------------------------------------------------------

    @Test
    void subscribeDeliversEventToListener() {
        List<ContinuumLoadEvent> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, seen::add);

        ContinuumLoadEvent event = new ContinuumLoadEvent(playerId);
        bus.dispatch(event);

        assertEquals(List.of(event), seen);
    }

    @Test
    void exactClassRouting_otherEventTypesNotDelivered() {
        List<ContinuumLoadEvent> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, seen::add);

        bus.dispatch(new ContinuumSaveEvent(playerId, SnapshotReason.AUTOSAVE));
        bus.dispatch(new ContinuumTransferEvent(playerId, "survival-1", "lobby"));

        assertTrue(seen.isEmpty());
    }

    @Test
    void moduleCaptureAndRestoreRouteSeparately() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumModuleCaptureEvent.class, e -> seen.add("capture"));
        bus.subscribe(ContinuumModuleRestoreEvent.class, e -> seen.add("restore"));

        bus.dispatch(new ContinuumModuleCaptureEvent(playerId, ModuleResult.ok(BuiltinModules.VITALS, 1)));
        bus.dispatch(new ContinuumModuleRestoreEvent(playerId, ModuleResult.ok(BuiltinModules.VITALS, 1)));

        assertEquals(List.of("capture", "restore"), seen);
    }

    @Test
    void supertypeSubscriptionsAreRejected() {
        // the sealed markers are not concrete members; exact-class routing cannot fire for them
        assertThrows(IllegalArgumentException.class,
                () -> bus.subscribe(ContinuumEvent.class, event -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> bus.subscribe(CancellableContinuumEvent.class, event -> { }));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> bus.subscribe(null, event -> { }));
        assertThrows(NullPointerException.class, () -> bus.subscribe(ContinuumLoadEvent.class, null));
        assertThrows(NullPointerException.class, () -> bus.dispatch(null));
    }

    // ---- ordering ----------------------------------------------------------------------

    @Test
    void listenersInvokedInRegistrationOrder() {
        List<String> order = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> order.add("first"));
        bus.subscribe(ContinuumLoadEvent.class, e -> order.add("second"));
        bus.subscribe(ContinuumLoadEvent.class, e -> order.add("third"));

        bus.dispatch(new ContinuumLoadEvent(playerId));

        assertEquals(List.of("first", "second", "third"), order);
    }

    @Test
    void subscribeDuringDispatchSeenOnNextDispatchOnly() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            seen.add("outer");
            bus.subscribe(ContinuumLoadEvent.class, e2 -> seen.add("inner"));
        });

        bus.dispatch(new ContinuumLoadEvent(playerId));
        assertEquals(List.of("outer"), seen);

        seen.clear();
        bus.dispatch(new ContinuumLoadEvent(playerId));
        assertEquals(List.of("outer", "inner"), seen);
    }

    // ---- error isolation ----------------------------------------------------------------

    @Test
    void throwingListenerDoesNotAffectOthers() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("before"));
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("after"));

        assertDoesNotThrow(() -> bus.dispatch(new ContinuumLoadEvent(playerId)));
        assertEquals(List.of("before", "after"), seen);
    }

    @Test
    void throwingErrorListenerDoesNotAffectOthers() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            throw new AssertionError("boom");
        });
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("after"));

        assertDoesNotThrow(() -> bus.dispatch(new ContinuumLoadEvent(playerId)));
        assertEquals(List.of("after"), seen);
    }

    @Test
    void throwingListenerIsNotUnsubscribed() {
        int[] calls = {0};
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            calls[0]++;
            throw new RuntimeException("boom");
        });

        bus.dispatch(new ContinuumLoadEvent(playerId));
        bus.dispatch(new ContinuumLoadEvent(playerId));

        assertEquals(2, calls[0]);
    }

    // ---- cancellation / veto -------------------------------------------------------------

    @Test
    void loadVetoPropagatesToLaterListeners() {
        List<Boolean> observed = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> e.cancel("maintenance mode"));
        bus.subscribe(ContinuumLoadEvent.class, e -> observed.add(e.isCancelled()));

        ContinuumLoadEvent event = new ContinuumLoadEvent(playerId);
        bus.dispatch(event);

        assertEquals(List.of(true), observed);
        assertTrue(event.isCancelled());
        assertEquals("maintenance mode", event.cancelReason());
    }

    @Test
    void saveVetoPropagatesToLaterListeners() {
        List<Boolean> observed = new ArrayList<>();
        bus.subscribe(ContinuumSaveEvent.class, e -> e.cancel("maintenance mode"));
        bus.subscribe(ContinuumSaveEvent.class, e -> observed.add(e.isCancelled()));

        ContinuumSaveEvent event = new ContinuumSaveEvent(playerId, SnapshotReason.TRANSFER);
        bus.dispatch(event);

        assertEquals(List.of(true), observed);
        assertTrue(event.isCancelled());
        assertEquals("maintenance mode", event.cancelReason());
    }

    @Test
    void transferVetoPropagatesToLaterListeners() {
        List<Boolean> observed = new ArrayList<>();
        bus.subscribe(ContinuumTransferEvent.class, e -> e.cancel("backend under maintenance"));
        bus.subscribe(ContinuumTransferEvent.class, e -> observed.add(e.isCancelled()));

        ContinuumTransferEvent event = new ContinuumTransferEvent(playerId, "survival-1", "lobby");
        bus.dispatch(event);

        assertEquals(List.of(true), observed);
        assertTrue(event.isCancelled());
        assertEquals("backend under maintenance", event.cancelReason());
    }

    @Test
    void transferVetoWorksWithUnknownDestination() {
        bus.subscribe(ContinuumTransferEvent.class, e -> e.cancel("not now"));

        ContinuumTransferEvent event = new ContinuumTransferEvent(playerId, "survival-1", null);
        bus.dispatch(event);

        assertTrue(event.isCancelled());
        assertNull(event.toBackendId());
    }

    @Test
    void cancelWithoutReasonIsAllowed() {
        ContinuumLoadEvent event = new ContinuumLoadEvent(playerId);
        bus.subscribe(ContinuumLoadEvent.class, e -> e.cancel(null));

        bus.dispatch(event);

        assertTrue(event.isCancelled());
        assertNull(event.cancelReason());
    }

    @Test
    void vetoDoesNotInterruptDispatch() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            e.cancel("no restore today");
            seen.add("vetoer");
        });
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("observer"));
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("last"));

        bus.dispatch(new ContinuumLoadEvent(playerId));

        // everyone hears the event; the veto just makes the outcome visible
        assertEquals(List.of("vetoer", "observer", "last"), seen);
    }

    // ---- subscription lifecycle ------------------------------------------------------------

    @Test
    void subscriptionCloseStopsFutureDispatch() {
        List<String> seen = new ArrayList<>();
        var subscription = bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("hit"));

        bus.dispatch(new ContinuumLoadEvent(playerId));
        subscription.close();
        bus.dispatch(new ContinuumLoadEvent(playerId));

        assertEquals(List.of("hit"), seen);
        assertFalse(subscription.isActive());
    }

    @Test
    void doubleCloseIsSafe() {
        var subscription = bus.subscribe(ContinuumLoadEvent.class, e -> { });
        subscription.close();
        assertDoesNotThrow(subscription::close);
        assertDoesNotThrow(subscription::unsubscribe);
        assertFalse(subscription.isActive());
    }

    @Test
    void inFlightUnsubscribeDoesNotStopCurrentDispatch() {
        List<String> seen = new ArrayList<>();
        var secondRef = new AtomicReference<lol.candyriya.continuum.api.event.EventSubscription>();
        // first listener unsubscribes the second one mid-dispatch; the dispatch snapshot was
        // already taken, so the second listener still hears this event
        bus.subscribe(ContinuumLoadEvent.class, e -> {
            seen.add("first");
            secondRef.get().unsubscribe();
        });
        var second = bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("second"));
        secondRef.set(second);

        bus.dispatch(new ContinuumLoadEvent(playerId));

        assertEquals(List.of("first", "second"), seen);
        assertFalse(second.isActive());
    }

    @Test
    void listenerUnsubscribingItselfIsNotCalledAgain() {
        List<String> seen = new ArrayList<>();
        var handle = new AtomicReference<lol.candyriya.continuum.api.event.EventSubscription>();
        handle.set(bus.subscribe(ContinuumLoadEvent.class, e -> {            seen.add("hit");
            handle.get().close();
        }));

        bus.dispatch(new ContinuumLoadEvent(playerId));
        bus.dispatch(new ContinuumLoadEvent(playerId));

        assertEquals(List.of("hit"), seen);
    }

    @Test
    void busCloseReleasesAllSubscriptions() {
        var first = bus.subscribe(ContinuumLoadEvent.class, e -> { });
        var second = bus.subscribe(ContinuumSaveEvent.class, e -> { });

        bus.close();
        bus.close(); // second close is a no-op

        assertFalse(first.isActive());
        assertFalse(second.isActive());
    }

    @Test
    void subscribeAfterCloseThrows() {
        bus.close();
        assertThrows(IllegalStateException.class,
                () -> bus.subscribe(ContinuumLoadEvent.class, e -> { }));
    }

    @Test
    void dispatchAfterCloseIsDroppedWithoutThrowing() {
        List<String> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, e -> seen.add("hit"));
        bus.close();

        assertDoesNotThrow(() -> bus.dispatch(new ContinuumLoadEvent(playerId)));
        assertTrue(seen.isEmpty());
    }

    @Test
    void subscriptionIsActiveUntilClosed() {
        var subscription = bus.subscribe(ContinuumLoadEvent.class, e -> { });
        assertTrue(subscription.isActive());
        subscription.unsubscribe();
        assertFalse(subscription.isActive());
    }

    // ---- every event fires through the bus -------------------------------------------------

    @Test
    void allTenEventsCanBeDispatched() {
        List<ContinuumEvent> seen = new ArrayList<>();
        bus.subscribe(ContinuumLoadEvent.class, seen::add);
        bus.subscribe(ContinuumSaveEvent.class, seen::add);
        bus.subscribe(ContinuumTransferEvent.class, seen::add);
        bus.subscribe(ContinuumRestoreEvent.class, seen::add);
        bus.subscribe(ContinuumModuleCaptureEvent.class, seen::add);
        bus.subscribe(ContinuumModuleRestoreEvent.class, seen::add);
        bus.subscribe(ContinuumSessionCreatedEvent.class, seen::add);
        bus.subscribe(ContinuumSessionReleasedEvent.class, seen::add);
        bus.subscribe(ContinuumRecoveryEvent.class, seen::add);
        bus.subscribe(ContinuumFailureEvent.class, seen::add);

        bus.dispatch(new ContinuumLoadEvent(playerId));
        bus.dispatch(new ContinuumSaveEvent(playerId, SnapshotReason.AUTOSAVE));
        bus.dispatch(new ContinuumTransferEvent(playerId, "survival-1", "lobby"));
        bus.dispatch(new ContinuumRestoreEvent(playerId, report()));
        bus.dispatch(new ContinuumModuleCaptureEvent(playerId, ModuleResult.ok(BuiltinModules.INVENTORY, 1)));
        bus.dispatch(new ContinuumModuleRestoreEvent(playerId, ModuleResult.ok(BuiltinModules.INVENTORY, 1)));
        bus.dispatch(new ContinuumSessionCreatedEvent(session(SessionState.ACTIVE)));
        bus.dispatch(new ContinuumSessionReleasedEvent(session(SessionState.RELEASED)));
        bus.dispatch(new ContinuumRecoveryEvent(session(SessionState.RECOVERY_REQUIRED)));
        bus.dispatch(new ContinuumFailureEvent(playerId, BuiltinModules.INVENTORY, "STORAGE_IO", "snapshot write failed"));

        assertEquals(10, seen.size());
        assertNotNull(seen.get(0).playerId());
    }
}
