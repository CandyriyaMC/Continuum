package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.CaptureResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.velocity.VelocityCatalogs;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.messaging.BackendMessenger;
import lol.candyriya.continuum.velocity.messaging.ContinuumChannels;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture seam for vanilla switches: no backend snapshot, no switch.
 * End-to-end through the real listener with the same fakes the handoff tests use.
 */
class CaptureOnSwitchListenerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("capture-on-switch-test");
    private static final I18nConfig I18N = new I18nConfig("en_US", "en_US", true);
    private static final PluginMessageCodec CODEC = new PluginMessageCodec();

    private RegisteredServer hub;
    private RegisteredServer survival;
    private HandoffFixtures.FakePlayer player;
    private HandoffFixtures.CountingMetrics metrics;
    private BackendMessenger messenger;
    private HandoffCoordinator coordinator;
    private CaptureOnSwitchListener listener;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        hub = HandoffFixtures.registeredServer("hub", 25565);
        survival = HandoffFixtures.registeredServer("survival", 25566);
        player = new HandoffFixtures.FakePlayer("vesper", UUID.randomUUID(), hub);
        metrics = new HandoffFixtures.CountingMetrics();
        messenger = new BackendMessenger(LOGGER, metrics, () -> 65536);
        coordinator = coordinator(8, false);
        listener = listener(true, () -> 3);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
        coordinator.close();
    }

    private HandoffCoordinator coordinator(long restoreTimeoutSeconds, boolean requireSnapshot) {
        HandoffFixtures.FakePlayer thePlayer = player;
        return new HandoffCoordinator(LOGGER, metrics, messenger, testMessages(),
                () -> List.of(hub, survival), () -> restoreTimeoutSeconds,
                () -> requireSnapshot,
                id -> id.equals(thePlayer.uuid) ? Optional.of(thePlayer.asProxy()) : Optional.empty());
    }

    private CaptureOnSwitchListener listener(boolean messagingEnabled, java.util.function.LongSupplier captureTimeoutSeconds) {
        return new CaptureOnSwitchListener(coordinator, messenger, testMessages(), metrics, LOGGER,
                () -> messagingEnabled, captureTimeoutSeconds);
    }

    private VelocityMessages testMessages() {
        Translator translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, null);
        return new VelocityMessages(() -> translator, () -> I18N,
                new lol.candyriya.continuum.velocity.PlayerLocaleResolver(
                        lol.candyriya.continuum.velocity.PlayerLocaleSource.none(), LOGGER));
    }

    private ServerPreConnectEvent preConnectTo(RegisteredServer target) {
        return new ServerPreConnectEvent(player.asProxy(), target, hub);
    }

    /** Waits until the listener fired its capture request and returns the decoded request. */
    private DecodedPluginMessage awaitCaptureRequest() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (!player.allPayloads.isEmpty()) {
                return CODEC.decode(player.allPayloads.get(player.allPayloads.size() - 1),
                        PluginMessageDirection.PROXY_TO_BACKEND);
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the listener never sent a capture request");
    }

    /** Answers the pending capture request on the connection the player sits on. */
    private void replyWith(CaptureResultMessage result, UUID correlationId) {
        ServerConnection source = player.asProxy().getCurrentServer().orElseThrow();
        byte[] frame = CODEC.encode(result, correlationId);
        listener.onPluginMessage(new PluginMessageEvent(source, source, ContinuumChannels.MAIN, frame));
    }

    private void runIntercept(ServerPreConnectEvent event) throws Exception {
        executor.submit(() -> listener.onServerPreConnect(event)).get(10, TimeUnit.SECONDS);
    }

    @Test
    void messagingDisabledSkipsInterceptionEntirely() throws Exception {
        CaptureOnSwitchListener disabled = listener(false, () -> 3);
        ServerPreConnectEvent event = preConnectTo(survival);

        executor.submit(() -> disabled.onServerPreConnect(event)).get(5, TimeUnit.SECONDS);

        assertTrue(event.getResult().isAllowed(), "with messaging disabled the switch must proceed untouched");
        assertTrue(player.allPayloads.isEmpty(), "no capture request may be fired");
    }

    @Test
    void sameServerSkipsInterception() throws Exception {
        ServerPreConnectEvent event = preConnectTo(hub);

        runIntercept(event);

        assertTrue(event.getResult().isAllowed());
        assertTrue(player.allPayloads.isEmpty());
    }

    @Test
    void capturedAnswerLetsTheSwitchThrough() throws Exception {
        ServerPreConnectEvent event = preConnectTo(survival);

        var future = executor.submit(() -> listener.onServerPreConnect(event));

        DecodedPluginMessage request = awaitCaptureRequest();
        CaptureRequestMessage capture = (CaptureRequestMessage) request.message();
        assertEquals(player.uuid, capture.targetUuid());
        assertEquals("switch", capture.reason());

        replyWith(new CaptureResultMessage(true, 101L, null), request.correlationId());
        future.get(5, TimeUnit.SECONDS);

        assertTrue(event.getResult().isAllowed(), "a captured player may switch");
        assertEquals(1, metrics.count("continuum.capture.allowed"));
        assertTrue(player.sentMessages.isEmpty(), "no notice is sent on the happy path");
    }

    @Test
    void declinedCaptureRefusesTheSwitch() throws Exception {
        ServerPreConnectEvent event = preConnectTo(survival);

        var future = executor.submit(() -> listener.onServerPreConnect(event));

        DecodedPluginMessage request = awaitCaptureRequest();
        replyWith(new CaptureResultMessage(false, null, "a required module failed"), request.correlationId());
        future.get(5, TimeUnit.SECONDS);

        assertFalse(event.getResult().isAllowed(), "a failed capture must refuse the switch");
        assertEquals(1, metrics.count("continuum.capture.denied"));
        assertEquals(1, player.sentMessages.size(), "the player is told why they stay");
    }

    @Test
    @Timeout(15)
    void noAnswerWithinTheTimeoutRefusesTheSwitch() throws Exception {
        CaptureOnSwitchListener impatient = listener(true, () -> 1);
        ServerPreConnectEvent event = preConnectTo(survival);

        var future = executor.submit(() -> impatient.onServerPreConnect(event));
        long started = System.nanoTime();
        awaitCaptureRequest(); // sanity: the request did fire
        future.get(10, TimeUnit.SECONDS);

        assertFalse(event.getResult().isAllowed());
        assertTrue((System.nanoTime() - started) >= TimeUnit.SECONDS.toNanos(1),
                "the wait must actually be bounded by the timeout");
        assertEquals(1, metrics.count("continuum.capture.timeout"));
        assertEquals(1, player.sentMessages.size());
    }

    @Test
    void unreachableBackendRefusesTheSwitch() throws Exception {
        player.connectionSendResult = false; // sendPluginMessage answers false: nothing reached the backend
        ServerPreConnectEvent event = preConnectTo(survival);

        runIntercept(event);

        assertFalse(event.getResult().isAllowed());
        assertEquals(1, metrics.count("continuum.capture.denied"));
        assertEquals(1, player.sentMessages.size(), "even a refusal before the wire must tell the player");
    }

    @Test
    void backToBackSwitchesDoNotAskTwiceInTheDebounceWindow() throws Exception {
        ServerPreConnectEvent first = preConnectTo(survival);
        var future = executor.submit(() -> listener.onServerPreConnect(first));
        DecodedPluginMessage request = awaitCaptureRequest();
        replyWith(new CaptureResultMessage(true, 101L, null), request.correlationId());
        future.get(5, TimeUnit.SECONDS);
        assertTrue(first.getResult().isAllowed());
        assertEquals(1, player.allPayloads.size());

        // a second switch inside the 2s window: no fresh capture, switch allowed
        ServerPreConnectEvent second = preConnectTo(survival);
        runIntercept(second);

        assertTrue(second.getResult().isAllowed());
        assertEquals(1, player.allPayloads.size(), "the debounce window must swallow the second request");
        assertTrue(player.sentMessages.isEmpty());
    }

    @Test
    void pendingTransferSkipsCaptureAndLetsHandoffDecide() throws Exception {
        // open a coordinated transfer for this player; the handoff flow is then in charge
        coordinator.initiate(player.asProxy(), "survival");
        assertEquals(1, coordinator.pendingCount());
        ServerPreConnectEvent event = preConnectTo(survival);

        runIntercept(event);

        assertTrue(event.getResult().isAllowed(), "the handoff listener owns the outcome for its own transfer");
        assertTrue(player.allPayloads.isEmpty(), "an in-flight transfer must not see a redundant capture request");
    }

    @Test
    void strayCaptureResultIsDroppedWithoutHarm() {
        ServerConnection source = player.asProxy().getCurrentServer().orElseThrow();
        byte[] frame = CODEC.encode(new CaptureResultMessage(true, 1L, null), UUID.randomUUID());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                listener.onPluginMessage(new PluginMessageEvent(source, source, ContinuumChannels.MAIN, frame)));
        assertEquals(0, metrics.count("continuum.capture.allowed"));
    }

    @Test
    void decisionMatrixIsPureAndTotal() {
        long now = System.currentTimeMillis();

        assertFalse(CaptureOnSwitchListener.shouldIntercept(false, false, "hub", "survival", now, null).intercept(),
                "messaging disabled must skip");
        assertEquals("messaging-disabled",
                CaptureOnSwitchListener.shouldIntercept(false, false, "hub", "survival", now, null).reason());

        assertEquals("not-on-a-backend",
                CaptureOnSwitchListener.shouldIntercept(true, false, null, "survival", now, null).reason());
        assertEquals("same-server",
                CaptureOnSwitchListener.shouldIntercept(true, false, "hub", "hub", now, null).reason());
        assertEquals("transfer-in-flight",
                CaptureOnSwitchListener.shouldIntercept(true, true, "hub", "survival", now, null).reason());
        assertEquals("debounce",
                CaptureOnSwitchListener.shouldIntercept(true, false, "hub", "survival", now, now - 500).reason());

        assertTrue(CaptureOnSwitchListener.shouldIntercept(true, false, "hub", "survival", now, now - 10_000).intercept(),
                "a capture older than the window does not debounce");
        assertTrue(CaptureOnSwitchListener.shouldIntercept(true, false, "hub", "survival", now, null).intercept(),
                "no prior capture means intercept");
    }
}
