package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.messaging.pluginmessage.PingMessage;
import lol.candyriya.continuum.paper.messaging.CaptureResponder;
import lol.candyriya.continuum.paper.transfer.TransferParticipant;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inbound half of the capture seam: proxy sends {@code continuum.capture.request},
 * this listener runs the pre-transfer capture and answers on the same correlation id.
 */
class CaptureResponderTest {

    private static final String CHANNEL = "continuum:main";
    private static final PluginMessageCodec CODEC = new PluginMessageCodec();

    private Fakes.FakePlayerStorage storage;
    private PlayerFifoQueue fifo;
    private ContinuumConfig config;
    private UUID playerId;
    private List<Fakes.RecordedPluginMessage> outbox;
    private Player player;
    private CaptureResponder responder;

    @BeforeEach
    void setUp() {
        storage = new Fakes.FakePlayerStorage();
        fifo = new PlayerFifoQueue(new OrchestrationSettings(16, 2, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30)));
        config = TestSupport.fullConfig();
        playerId = UUID.randomUUID();
        outbox = new CopyOnWriteArrayList<>();
        player = Fakes.recordingPlayer(playerId, outbox);
        responder = new CaptureResponder(Fakes.fakePlugin(), transferFor(List.of(
                TestSupport.spec(BuiltinModules.ATTRIBUTES, false, FailurePolicy.ABORT),
                TestSupport.spec(BuiltinModules.INVENTORY, false, FailurePolicy.ABORT))),
                CHANNEL, LoggerFactory.getLogger("capture-responder-test"));
    }

    @AfterEach
    void tearDown() {
        fifo.close();
    }

    private TransferParticipant transferFor(List<ModuleRuntime.RegisteredModule> specs) {
        ModuleRuntime runtime = ModuleRuntime.of(specs, List.of());
        SnapshotManager manager = new SnapshotManager(Fakes.fakePlugin(), config, runtime, fifo,
                new DirtyTracker(), new SessionRegistry(), storage, () -> true,
                new EventDispatcher(), new OrchestrationSettings(16, 2, Duration.ofMinutes(5),
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30)), id -> false, new LastKnownGoodStore());
        return new TransferParticipant(manager);
    }

    private byte[] captureRequestFrame(UUID target, UUID correlation) {
        return CODEC.encode(new CaptureRequestMessage(target, "switch"), correlation);
    }

    private DecodedPluginMessage awaitReply() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!outbox.isEmpty()) {
                Fakes.RecordedPluginMessage sent = outbox.get(outbox.size() - 1);
                DecodedPluginMessage decoded = CODEC.decode(sent.payload(), PluginMessageDirection.BACKEND_TO_PROXY);
                assertInstanceOf(CaptureResultMessage.class, decoded.message());
                return decoded;
            }
            Thread.sleep(10);
        }
        fail("the responder never answered");
        throw new IllegalStateException("unreachable");
    }

    @Test
    @Timeout(10)
    void persistedCaptureAnswersCapturedTrueWithSnapshotId() throws Exception {
        UUID correlation = UUID.randomUUID();

        responder.onPluginMessageReceived(CHANNEL, player, captureRequestFrame(playerId, correlation));

        DecodedPluginMessage decoded = awaitReply();
        assertEquals(correlation, decoded.correlationId(), "the reply must ride the request's correlation id");
        assertEquals(CHANNEL, outbox.get(outbox.size() - 1).channel());
        CaptureResultMessage result = (CaptureResultMessage) decoded.message();
        assertTrue(result.captured());
        assertNotNull(result.snapshotId(), "a persisted capture must report its snapshot id");
        assertTrue(result.snapshotId() >= 1);
        assertNull(result.detail());
        assertEquals(1, storage.saveCalls());
    }

    @Test
    @Timeout(10)
    void failingModuleAnswersCapturedFalseWithDetail() throws Exception {
        storage.failOnSave();
        UUID correlation = UUID.randomUUID();

        responder.onPluginMessageReceived(CHANNEL, player, captureRequestFrame(playerId, correlation));

        CaptureResultMessage result = (CaptureResultMessage) awaitReply().message();
        assertFalse(result.captured());
        assertNull(result.snapshotId());
        assertNotNull(result.detail(), "a failed capture must say why");
        assertEquals(0, storage.saveCalls(), "nothing may be written when the write itself fails");
    }

    @Test
    @Timeout(10)
    void strayCorrelationStillGetsAnAnswer() throws Exception {
        // the connection's player is the only target we may capture; anything else is refused loudly
        UUID someoneElse = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        responder.onPluginMessageReceived(CHANNEL, player, captureRequestFrame(someoneElse, correlation));

        CaptureResultMessage result = (CaptureResultMessage) awaitReply().message();
        assertFalse(result.captured());
        assertTrue(result.detail().contains("match"), result.detail());
        assertEquals(0, storage.saveCalls(), "refused requests must not touch storage");
    }

    @Test
    void malformedFrameIsDroppedWithoutSilenceContract() {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05};

        assertDoesNotThrow(() -> responder.onPluginMessageReceived(CHANNEL, player, garbage));

        assertTrue(outbox.isEmpty(), "an undecodable frame carries no correlation to answer with");
        assertEquals(0, storage.saveCalls());
    }

    @Test
    void proxyBoundFramesGetNoAnswer() {
        // a capture result (or any backend->proxy kind) arriving here must be ignored, not bounced
        byte[] resultFrame = CODEC.encode(new CaptureResultMessage(true, 1L, null), UUID.randomUUID());
        assertDoesNotThrow(() -> responder.onPluginMessageReceived(CHANNEL, player, resultFrame));
        assertTrue(outbox.isEmpty());
        assertEquals(0, storage.saveCalls());

        byte[] pingFrame = CODEC.encode(new PingMessage(Instant.ofEpochMilli(1_700_000_000_000L)), UUID.randomUUID());
        assertDoesNotThrow(() -> responder.onPluginMessageReceived(CHANNEL, player, pingFrame));
        assertTrue(outbox.isEmpty());
    }

    @Test
    void anotherKindOnOurChannelIsIgnored() {
        byte[] request = CODEC.encode(new HandoffRequestMessage(playerId, "survival-1", "switch world"), UUID.randomUUID());

        assertDoesNotThrow(() -> responder.onPluginMessageReceived(CHANNEL, player, request));

        assertTrue(outbox.isEmpty(), "the handoff handshake is serviced elsewhere, not by this listener");
        assertEquals(0, storage.saveCalls());
    }

    @Test
    @Timeout(10)
    void backToBackRequestsAreRateLimited() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        responder.onPluginMessageReceived(CHANNEL, player, captureRequestFrame(playerId, first));
        // not waiting for the first to land — the second must be refused within the window
        responder.onPluginMessageReceived(CHANNEL, player, captureRequestFrame(playerId, second));

        CaptureResultMessage rateLimited = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (outbox.size() >= 2) {
                rateLimited = outbox.stream()
                        .map(s -> (CaptureResultMessage) CODEC.decode(s.payload(), PluginMessageDirection.BACKEND_TO_PROXY).message())
                        .filter(r -> r.detail() != null && r.detail().contains("within the last second"))
                        .findFirst().orElse(null);
                if (rateLimited != null) {
                    break;
                }
            }
            Thread.sleep(10);
        }
        assertNotNull(rateLimited, "the second request inside the window must be rate-limited");
        assertFalse(rateLimited.captured());
        assertEquals(1, storage.saveCalls(), "only one capture may be honored in the window");
    }

    @Test
    void wrongChannelIsIgnored() {
        UUID correlation = UUID.randomUUID();

        responder.onPluginMessageReceived("continuum:other", player, captureRequestFrame(playerId, correlation));

        assertTrue(outbox.isEmpty());
        assertEquals(0, storage.saveCalls());
    }
}
