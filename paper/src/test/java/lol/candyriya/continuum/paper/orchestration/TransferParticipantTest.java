package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.paper.transfer.TransferCaptureOutcome;
import lol.candyriya.continuum.paper.transfer.TransferParticipant;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferParticipantTest {

    private Fakes.FakePlayerStorage storage;
    private PlayerFifoQueue fifo;
    private Player player;
    private TransferParticipant transfer;
    private ContinuumConfig config;

    @BeforeEach
    void setUp() {
        storage = new Fakes.FakePlayerStorage();
        fifo = new PlayerFifoQueue(new OrchestrationSettings(16, 2, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30)));
        UUID playerId = UUID.randomUUID();
        player = Fakes.fakePlayer(playerId);
        config = TestSupport.fullConfig();
        transfer = build(List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.INVENTORY, false, FailurePolicy.ABORT)));
    }

    @AfterEach
    void tearDown() {
        fifo.close();
    }

    private TransferParticipant build(List<ModuleRuntime.RegisteredModule> specs) {
        ModuleRuntime runtime = ModuleRuntime.of(specs, List.of());
        SnapshotManager manager = new SnapshotManager(Fakes.fakePlugin(), config, runtime, fifo,
                new DirtyTracker(), new SessionRegistry(), storage, () -> true,
                new EventDispatcher(), new OrchestrationSettings(16, 2, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30)), id -> false, new LastKnownGoodStore());
        return new TransferParticipant(manager);
    }

    @Test
    void successfulTransferCaptureReportsPersistedSnapshot() throws Exception {
        TransferCaptureOutcome outcome = transfer.prepareTransfer(player).get(5, TimeUnit.SECONDS);

        assertTrue(outcome.snapshotPersisted());
        assertEquals(SyncResult.SUCCESS, outcome.result());
        assertEquals(1, storage.saveCalls());
        assertEquals("TRANSFER", storage.saveOptionsSeen().get(0).reason());
    }

    @Test
    void failingRequiredModuleReportsNotPersisted() throws Exception {
        TransferParticipant broken = build(List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, true, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.INVENTORY, false, FailurePolicy.ABORT)));

        TransferCaptureOutcome outcome = broken.prepareTransfer(player).get(5, TimeUnit.SECONDS);

        assertFalse(outcome.snapshotPersisted());
        assertEquals(SyncResult.FAILED, outcome.result());
        assertEquals(0, storage.saveCalls(), "a required module failure must not write anything");
    }
}
