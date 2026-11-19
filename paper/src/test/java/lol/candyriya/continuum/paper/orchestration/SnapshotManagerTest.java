package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.event.ContinuumFailureEvent;
import lol.candyriya.continuum.api.event.ContinuumSaveEvent;
import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.report.CaptureReport;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;
import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotManagerTest {

    private Fakes.FakePlayerStorage storage;
    private EventDispatcher events;
    private DirtyTracker dirty;
    private SessionRegistry sessions;
    private PlayerFifoQueue fifo;
    private Player player;
    private UUID playerId;
    private ContinuumConfig config;

    @BeforeEach
    void setUp() {
        storage = new Fakes.FakePlayerStorage();
        events = new EventDispatcher();
        dirty = new DirtyTracker();
        sessions = new SessionRegistry();
        fifo = new PlayerFifoQueue(new OrchestrationSettings(16, 2, java.time.Duration.ofMinutes(5),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(30)));
        playerId = UUID.randomUUID();
        player = Fakes.fakePlayer(playerId);
        config = TestSupport.fullConfig();
    }

    @AfterEach
    void tearDown() {
        fifo.close();
    }

    private SnapshotManager manager(List<ModuleRuntime.RegisteredModule> modules) {
        ModuleRuntime runtime = ModuleRuntime.of(modules, List.of());
        return new SnapshotManager(Fakes.fakePlugin(), config, runtime, fifo, dirty, sessions,
                storage, () -> true, events, new OrchestrationSettings(16, 2, java.time.Duration.ofMinutes(5),
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(30)),
                id -> false, new LastKnownGoodStore());
    }

    private List<ModuleRuntime.RegisteredModule> standardSpecs() {
        return List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.INVENTORY, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.VITALS, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.RECIPES, false, FailurePolicy.SKIP));
    }

    private CaptureReport capture(SnapshotManager manager, SnapshotReason reason) throws Exception {
        return manager.capture(player, reason).get(5, TimeUnit.SECONDS);
    }

    @Test
    void fullCapturePersistsAllModulesAndReportsSuccess() throws Exception {
        try (SnapshotManager manager = manager(standardSpecs())) {
            CaptureReport report = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.SUCCESS, report.result());
            assertEquals(4, report.modules().size());
            assertTrue(report.snapshotIdIfPersisted().isPresent());
            assertEquals(1, storage.saveCalls());
            SnapshotEnvelope stored = storage.stored();
            assertNotNull(stored);
            assertEquals(List.of(BuiltinModules.ATTRIBUTES, BuiltinModules.INVENTORY,
                            BuiltinModules.VITALS, BuiltinModules.RECIPES).size(),
                    stored.modules().size());
            // dirty flags are cleared by the merged persist
            assertFalse(dirty.isAnyDirty(playerId));
            assertNotNull(manager.lastKnownGood(playerId));
        }
    }

    @Test
    void outboxDraftRidesTheSameSaveTransaction() throws Exception {
        try (SnapshotManager manager = manager(standardSpecs())) {
            capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(1, storage.saveOptionsSeen().size());
            var draft = storage.saveOptionsSeen().get(0).outbox().getFirst();
            assertEquals("continuum.snapshot.updated", draft.msgType());
            assertTrue(draft.payloadJson().contains("\"snapshot_id\""));
        }
    }

    @Test
    void requiredModuleFailureAbortsTheSaveEntirely() throws Exception {
        List<ModuleRuntime.RegisteredModule> specs = List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, true, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.INVENTORY, false, FailurePolicy.ABORT));
        try (SnapshotManager manager = manager(specs)) {
            CaptureReport report = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.FAILED, report.result());
            assertEquals(0, storage.saveCalls());
            assertNull(storage.stored());
            var failed = report.modules().stream()
                    .filter(m -> m.moduleId().equals(BuiltinModules.ATTRIBUTES))
                    .findFirst().orElseThrow();
            assertEquals("CAPTURE_FAILED", failed.code());
            assertTrue(dirty.isAnyDirty(playerId), "failed required module marks everything dirty");
        }
    }

    @Test
    void optionalModuleFailureDegradesToPartialAndPersistsTheRest() throws Exception {
        List<ModuleRuntime.RegisteredModule> specs = List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.RECIPES, true, FailurePolicy.SKIP));
        try (SnapshotManager manager = manager(specs)) {
            CaptureReport report = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.PARTIAL, report.result());
            assertEquals(1, storage.saveCalls());
            SnapshotEnvelope stored = storage.stored();
            assertNotNull(stored);
            assertEquals(List.of(BuiltinModules.ATTRIBUTES),
                    stored.modules().stream().map(ModuleSnapshot::moduleId).toList());
        }
    }

    @Test
    void vetoByEventListenerSkipsTheCaptureEntirely() throws Exception {
        events.subscribe(ContinuumSaveEvent.class, event -> event.cancel("maintenance"));
        try (SnapshotManager manager = manager(standardSpecs())) {
            CaptureReport report = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.SUCCESS, report.result());
            assertEquals(0, report.modules().size());
            assertEquals(0, storage.saveCalls());
        }
    }

    @Test
    void staleTokenSaveIsReportedAndNeverSilent() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        events.subscribe(ContinuumFailureEvent.class, event -> failures.incrementAndGet());
        try (SnapshotManager manager = manager(standardSpecs())) {
            CaptureReport first = capture(manager, SnapshotReason.DISCONNECT);
            assertEquals(SyncResult.SUCCESS, first.result());

            storage.forceStoredToken(10_000);
            CaptureReport second = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.FAILED, second.result());
            assertFalse(second.snapshotIdIfPersisted().isPresent());
            assertEquals(1, failures.get(), "stale token produces exactly one failure event");
            assertNull(manager.lastKnownGood(playerId), "stale write drops the bookmark");
            assertTrue(dirty.isAnyDirty(playerId), "stale write forces a full next capture");
        }
    }

    @Test
    void autosaveWithNothingDirtyWritesNothing() throws Exception {
        try (SnapshotManager manager = manager(standardSpecs())) {
            CaptureReport prime = capture(manager, SnapshotReason.DISCONNECT);
            assertEquals(SyncResult.SUCCESS, prime.result());
            int savesAfterPrime = storage.saveCalls();

            CaptureReport autosave = capture(manager, SnapshotReason.AUTOSAVE);

            assertEquals(SyncResult.SUCCESS, autosave.result());
            assertEquals(0, autosave.modules().size());
            assertEquals(savesAfterPrime, storage.saveCalls(), "a clean autosave must not rewrite the row");
        }
    }

    @Test
    void autosaveReCapturesOnlyDirtyTrackedModulesAndCarriesTheRest() throws Exception {
        TestSupport.FakeModule inventoryProbe = new TestSupport.FakeModule(BuiltinModules.INVENTORY, false);
        TestSupport.FakeModule vitalsProbe = new TestSupport.FakeModule(BuiltinModules.VITALS, false);
        List<ModuleRuntime.RegisteredModule> specs = List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, false, FailurePolicy.ABORT),
                new ModuleRuntime.RegisteredModule(BuiltinModules.INVENTORY, inventoryProbe,
                        TestSupport.TestPayload.class, FailurePolicy.ABORT, true),
                new ModuleRuntime.RegisteredModule(BuiltinModules.VITALS, vitalsProbe,
                        TestSupport.TestPayload.class, FailurePolicy.ABORT, true),
                TestSupport.spec(BuiltinModules.RECIPES, false, FailurePolicy.SKIP));
        try (SnapshotManager manager = manager(specs)) {
            capture(manager, SnapshotReason.DISCONNECT);
            int inventoryBefore = inventoryProbe.captureCalls();
            int vitalsBefore = vitalsProbe.captureCalls();

            dirty.markDirty(playerId, BuiltinModules.INVENTORY);
            CaptureReport autosave = capture(manager, SnapshotReason.AUTOSAVE);

            assertEquals(SyncResult.SUCCESS, autosave.result());
            assertEquals(inventoryBefore + 1, inventoryProbe.captureCalls(), "dirty inventory re-captures");
            assertEquals(vitalsBefore, vitalsProbe.captureCalls(), "clean vitals is carried over from the LKG");
            SnapshotEnvelope stored = storage.stored();
            assertNotNull(stored);
            assertEquals(4, stored.modules().size(), "carried vitals rides along in the merged envelope");
        }
    }

    @Test
    void sessionIsReleasedAfterCaptureAndNeverTwice() throws Exception {
        try (SnapshotManager manager = manager(standardSpecs())) {
            capture(manager, SnapshotReason.DISCONNECT);

            Boolean released = manager.releaseSession(playerId).get(5, TimeUnit.SECONDS);

            assertTrue(released);
            assertEquals(1, storage.released().size());
            // second release is a no-op on the registry
            Boolean again = manager.releaseSession(playerId).get(5, TimeUnit.SECONDS);
            assertFalse(again);
            assertEquals(1, storage.released().size());
        }
    }

    @Test
    void failOnSaveStorageErrorIsReportedNotThrown() throws Exception {
        storage.failOnSave();
        try (SnapshotManager manager = manager(standardSpecs())) {
            CaptureReport report = capture(manager, SnapshotReason.DISCONNECT);

            assertEquals(SyncResult.FAILED, report.result());
            assertFalse(report.snapshotIdIfPersisted().isPresent());
        }
    }
}
