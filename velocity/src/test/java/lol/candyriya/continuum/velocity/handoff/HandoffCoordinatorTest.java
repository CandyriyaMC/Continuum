package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.common.session.TransferState;
import lol.candyriya.continuum.velocity.VelocityCatalogs;
import lol.candyriya.continuum.velocity.VelocityMessages;
import lol.candyriya.continuum.velocity.VelocityText;
import lol.candyriya.continuum.velocity.messaging.BackendMessenger;

import lol.candyriya.continuum.messaging.pluginmessage.DecodedPluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffReadyMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffRequestMessage;
import lol.candyriya.continuum.messaging.pluginmessage.HandoffResumedMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessage;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageCodec;
import lol.candyriya.continuum.messaging.pluginmessage.PluginMessageDirection;
import lol.candyriya.continuum.messaging.pluginmessage.RouteOutcome;
import lol.candyriya.continuum.messaging.pluginmessage.RouteResultMessage;
import lol.candyriya.continuum.messaging.pluginmessage.SessionTokenMessage;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffCoordinatorTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("handoff-test");
    private static final I18nConfig I18N = new I18nConfig("en_US", "en_US", true);
    private static final PluginMessageCodec CODEC = new PluginMessageCodec();

    private RegisteredServer hub;
    private RegisteredServer survival;
    private HandoffFixtures.FakePlayer player;
    private HandoffFixtures.CountingMetrics metrics;
    private HandoffFixtures.FakeConnection sourceHub;
    private HandoffFixtures.FakeConnection sourceSurvival;

    @BeforeEach
    void setUp() {
        hub = HandoffFixtures.registeredServer("hub", 25565);
        survival = HandoffFixtures.registeredServer("survival", 25566);
        player = new HandoffFixtures.FakePlayer("vesper", UUID.randomUUID(), hub);
        metrics = new HandoffFixtures.CountingMetrics();
        sourceHub = player.connectionTo(hub);
        sourceSurvival = player.connectionTo(survival);
    }

    private VelocityMessages testMessages() {
        Translator translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, null);
        return new VelocityMessages(() -> translator, () -> I18N,
                new lol.candyriya.continuum.velocity.PlayerLocaleResolver(lol.candyriya.continuum.velocity.PlayerLocaleSource.none(), LOGGER));
    }

    private HandoffCoordinator coordinator(long restoreTimeoutSeconds, boolean requireSnapshot) {
        BackendMessenger messenger = new BackendMessenger(LOGGER, metrics, () -> 65536);
        HandoffFixtures.FakePlayer thePlayer = player;
        return new HandoffCoordinator(LOGGER, metrics, messenger, testMessages(),
                () -> List.of(hub, survival), () -> restoreTimeoutSeconds,
                () -> requireSnapshot,
                id -> id.equals(thePlayer.uuid) ? Optional.of(thePlayer.asProxy()) : Optional.empty());
    }

    private byte[] encode(PluginMessage message) {
        return CODEC.encode(message, UUID.randomUUID());
    }

    private byte[] encode(PluginMessage message, UUID correlation) {
        return CODEC.encode(message, correlation);
    }

    private DecodedPluginMessage decodeReply(byte[] frame) {
        return CODEC.decode(frame, PluginMessageDirection.PROXY_TO_BACKEND);
    }

    private byte[] lastPayload(HandoffFixtures.FakeConnection connection) {
        return connection.sentPayloads.get(connection.sentPayloads.size() - 1);
    }

    @Test
    void initiateRefusedWhenSnapshotIsRequired() {
        HandoffCoordinator coordinator = coordinator(8, true);

        InitiateResult result = coordinator.initiate(player.asProxy(), "survival");

        assertFalse(result.initiated());
        assertTrue(result.reason().contains("snapshot"));
        assertNull(player.lastRequest, "no connection may be attempted");
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void initiateDrivesUnconfirmedSwitchWhenSnapshotIsNotRequired() {
        HandoffCoordinator coordinator = coordinator(8, false);

        InitiateResult result = coordinator.initiate(player.asProxy(), "survival");

        assertTrue(result.initiated());
        assertEquals("survival", result.targetBackend());
        assertNotNull(player.lastRequest);
        assertEquals(1, player.connectionRequestCount);
        PendingHandoff record = coordinator.pendingHandoff(player.uuid).orElseThrow();
        assertEquals(TransferState.CONNECTING, record.phase());
        assertEquals("survival", record.targetBackend());
    }

    @Test
    void initiateWithUnknownTargetIsRefused() {
        HandoffCoordinator coordinator = coordinator(8, false);

        InitiateResult result = coordinator.initiate(player.asProxy(), "nope");

        assertFalse(result.initiated());
        assertNull(player.lastRequest);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void initiateOntoTheCurrentBackendIsRefused() {
        HandoffCoordinator coordinator = coordinator(8, false);

        InitiateResult result = coordinator.initiate(player.asProxy(), "hub");

        assertFalse(result.initiated());
        assertTrue(result.reason().contains("already connected"));
        assertEquals(0, player.connectionRequestCount);
    }

    @Test
    void initiateTwiceWhileInFlightIsRefused() {
        HandoffCoordinator coordinator = coordinator(8, false);

        assertTrue(coordinator.initiate(player.asProxy(), "survival").initiated());
        InitiateResult second = coordinator.initiate(player.asProxy(), "survival");

        assertFalse(second.initiated());
        assertTrue(second.reason().contains("already in flight"));
        assertEquals(1, player.connectionRequestCount, "no second connection may be driven");
        assertEquals(1, metrics.count("continuum.handoff.denied"));
    }

    @Test
    void backendRequestIsApprovedAndThePlayerIsMoved() {
        HandoffCoordinator coordinator = coordinator(8, false);
        UUID correlation = UUID.randomUUID();

        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer"), correlation));

        // the reply is the ALLOWED route result, correlated with the request
        assertEquals(1, sourceHub.sentPayloads.size());
        DecodedPluginMessage reply = decodeReply(lastPayload(sourceHub));
        assertEquals(correlation, reply.correlationId());
        RouteResultMessage route = (RouteResultMessage) reply.message();
        assertEquals(RouteOutcome.ALLOWED, route.outcome());
        assertEquals("survival", route.targetServer());
        assertEquals(player.uuid, route.player());
        // and the player is being switched
        assertEquals(1, player.connectionRequestCount);
        PendingHandoff record = coordinator.pendingHandoff(player.uuid).orElseThrow();
        assertEquals(TransferState.CONNECTING, record.phase());
    }

    @Test
    void backendRequestWithUnknownTargetIsDenied() {
        HandoffCoordinator coordinator = coordinator(8, false);
        UUID correlation = UUID.randomUUID();

        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "nope", "transfer"), correlation));

        RouteResultMessage route = (RouteResultMessage) decodeReply(lastPayload(sourceHub)).message();
        assertEquals(RouteOutcome.DENIED, route.outcome());
        assertEquals("", route.targetServer());
        assertTrue(route.reason().contains("nope"));
        assertEquals(0, player.connectionRequestCount);
        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, metrics.count("continuum.handoff.denied"));
    }

    @Test
    void backendRequestOntoTheCurrentBackendIsDenied() {
        HandoffCoordinator coordinator = coordinator(8, false);

        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "hub", "transfer")));

        RouteResultMessage route = (RouteResultMessage) decodeReply(lastPayload(sourceHub)).message();
        assertEquals(RouteOutcome.DENIED, route.outcome());
        assertTrue(route.reason().contains("already connected"));
        assertEquals(0, player.connectionRequestCount);
    }

    @Test
    void frameFromAConnectionWhosePlayerDiffersIsDropped() {
        HandoffCoordinator coordinator = coordinator(8, false);

        coordinator.handleBackendFrame(sourceHub,
                encode(new HandoffRequestMessage(UUID.randomUUID(), "survival", "transfer")));

        assertTrue(sourceHub.sentPayloads.isEmpty(), "no reply may be written for a forged frame");
        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, metrics.count("continuum.handoff.bad_frame"));
    }

    @Test
    void garbageFrameIsDropped() {
        HandoffCoordinator coordinator = coordinator(8, false);

        coordinator.handleBackendFrame(sourceHub, new byte[]{0x10, 0x20, 0x30});

        assertTrue(sourceHub.sentPayloads.isEmpty());
        assertEquals(1, metrics.count("continuum.handoff.bad_frame"));
    }

    @Test
    void secondBackendRequestWhileInFlightIsDeniedWithoutASecondConnect() {
        HandoffCoordinator coordinator = coordinator(8, false);

        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        assertEquals(1, player.connectionRequestCount, "no second connect may be driven");
        RouteResultMessage second = (RouteResultMessage) decodeReply(lastPayload(sourceHub)).message();
        assertEquals(RouteOutcome.DENIED, second.outcome());
        assertTrue(second.reason().contains("already in flight"));
    }

    @Test
    void landsOnTargetAndCommitsOnResumed() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        coordinator.onServerConnected(player.asProxy(), survival);
        assertEquals(TransferState.RESTORING, coordinator.pendingHandoff(player.uuid).orElseThrow().phase());

        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffResumedMessage(player.uuid, "survival", "restored")));

        assertEquals(0, coordinator.pendingCount(), "the record is gone after commit");
        assertEquals(1, metrics.count("continuum.handoff.committed"));
        assertTrue(player.sentMessages.stream()
                .map(VelocityText::plain)
                .anyMatch(text -> text.contains("Welcome to survival.")), "the player is told the transfer finished");
    }

    @Test
    void readyIssuesASessionTokenWhileRestoring() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        UUID correlation = UUID.randomUUID();
        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffReadyMessage(player.uuid, "survival", true, "warm"), correlation));

        SessionTokenMessage token = (SessionTokenMessage) decodeReply(lastPayload(sourceSurvival)).message();
        assertEquals(player.uuid, token.player());
        assertTrue(token.token().matches("[0-9a-f]{48}"), "the ticket is 24 random bytes as hex");
        assertTrue(token.expiresAt().isAfter(java.time.Instant.now()), "the ticket expires in the future");
        assertEquals(TransferState.RESTORING, coordinator.pendingHandoff(player.uuid).orElseThrow().phase(),
                "READY does not advance the transfer; RESUMED does");
        assertEquals(decodeReply(lastPayload(sourceSurvival)).correlationId(), correlation);
        assertEquals(1, metrics.count("continuum.handoff.token_issued"));
    }

    @Test
    void readyFromTheWrongServerIsIgnored() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        HandoffFixtures.FakeConnection imposter = player.connectionTo(hub);
        coordinator.handleBackendFrame(imposter, encode(new HandoffReadyMessage(player.uuid, "hub", true, "")));

        assertTrue(imposter.sentPayloads.isEmpty(), "no token may go to the wrong backend");
        assertEquals(1, metrics.count("continuum.handoff.ready_mismatch"));
        assertEquals(TransferState.RESTORING, coordinator.pendingHandoff(player.uuid).orElseThrow().phase());
    }

    @Test
    void readyDeclinedDuringRestoreKicksWithRestoreFailure() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffReadyMessage(player.uuid, "survival", false, "not now")));

        assertNotNull(player.disconnectReason, "the frozen player must be disconnected");
        assertTrue(VelocityText.plain(player.disconnectReason).contains("couldn't restore your data"));
        assertEquals(1, metrics.count("continuum.handoff.recovery_required"));
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void readyDeclinedBeforeLandingFailsWithoutKicking() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffReadyMessage(player.uuid, "survival", false, "nah")));

        assertNull(player.disconnectReason, "the player is still connected to the source; no kick");
        assertEquals(0, metrics.count("continuum.handoff.recovery_required"));
        assertTrue(metrics.count("continuum.handoff.failed") >= 1);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void resumedBeforeTheLandingEventCommitsAnyway() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        // the connection bookkeeping has not landed yet; RESUMED may overtake it
        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffResumedMessage(player.uuid, "survival", "restored")));

        assertEquals(0, coordinator.pendingCount());
        assertEquals(1, metrics.count("continuum.handoff.committed"));
    }

    @Test
    void resumedTwiceCommitsOnce() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffResumedMessage(player.uuid, "survival", "restored")));
        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffResumedMessage(player.uuid, "survival", "restored again")));

        assertEquals(1, metrics.count("continuum.handoff.committed"));
        assertEquals(1, metrics.count("continuum.handoff.stray_resumed"));
    }

    @Test
    void kickedFromTheTargetDuringRestoreMarksRecoveryRequired() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        coordinator.onKickedFromServer(player.asProxy(), survival);

        assertEquals(1, metrics.count("continuum.handoff.recovery_required"));
        assertEquals(0, coordinator.pendingCount());
        assertNull(player.disconnectReason, "the backend already kicked the player; no double kick");
    }

    @Test
    void kickedMidHandoffFailsTheTransfer() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        coordinator.onKickedFromServer(player.asProxy(), hub);

        assertEquals(0, metrics.count("continuum.handoff.recovery_required"));
        assertTrue(metrics.count("continuum.handoff.failed") >= 1);
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void disconnectAbandonsTheHandoff() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        coordinator.onDisconnect(player.asProxy());

        assertEquals(0, coordinator.pendingCount());
        // a RESUMED that arrives after the player left matches nothing
        coordinator.handleBackendFrame(sourceSurvival, encode(new HandoffResumedMessage(player.uuid, "survival", "restored")));
        assertEquals(1, metrics.count("continuum.handoff.stray_resumed"));
        assertEquals(0, metrics.count("continuum.handoff.committed"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void restoreTimeoutDisconnectsWithRestoreFailure() throws InterruptedException {
        HandoffCoordinator coordinator = coordinator(1, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        long waitUntil = System.currentTimeMillis() + 4000;
        while (player.disconnectReason == null && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(50);
        }

        assertNotNull(player.disconnectReason, "the frozen player must be disconnected once the restore timed out");
        assertTrue(VelocityText.plain(player.disconnectReason).contains("couldn't restore your data"));
        assertEquals(1, metrics.count("continuum.handoff.recovery_required"));
        assertEquals(0, coordinator.pendingCount());
    }

    @Test
    void sidewaysConnectionsAreDeniedWhileTheTransferIsInFlight() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));

        assertTrue(player.connectionRequestCount == 1);
        assertFalse(coordinator.denySidewaysMove(player.asProxy(), survival),
                "the connect the coordinator started must go through");
        assertTrue(coordinator.denySidewaysMove(player.asProxy(), hub),
                "a sideways hop while the transfer is in flight is refused");
        // and without any record nobody restricts the player at all
        coordinator.onDisconnect(player.asProxy());
        assertFalse(coordinator.denySidewaysMove(player.asProxy(), survival));
    }

    @Test
    void resumedWithMismatchedServerIsIgnored() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffRequestMessage(player.uuid, "survival", "transfer")));
        coordinator.onServerConnected(player.asProxy(), survival);

        // a RESUMED claiming to be a different backend must not commit the transfer
        coordinator.handleBackendFrame(sourceHub, encode(new HandoffResumedMessage(player.uuid, "hub", "restored")));

        assertEquals(1, metrics.count("continuum.handoff.resumed_mismatch"));
        assertEquals(TransferState.RESTORING, coordinator.pendingHandoff(player.uuid).orElseThrow().phase());
    }

    @Test
    void initiateIsRefusedAfterClose() {
        HandoffCoordinator coordinator = coordinator(8, false);
        coordinator.close();

        InitiateResult result = coordinator.initiate(player.asProxy(), "survival");

        assertFalse(result.initiated());
        assertEquals(0, player.connectionRequestCount);
    }

    @Test
    void connectFailureReleasesThePendingSlot() {
        HandoffFixtures.FakePlayer doomed = new HandoffFixtures.FakePlayer("vee", UUID.randomUUID(), hub);
        // this player's connection request completes with a non-success status once the pipeline runs
        doomed.connectStatus = ConnectionRequestBuilder.Status.CONNECTION_CANCELLED;
        HandoffCoordinator coordinator = coordinator(8, false);

        InitiateResult result = coordinator.initiate(doomed.asProxy(), "survival");

        // the pipeline was entered; the failure surfaces through the completion future,
        // which runs synchronously on the calling thread for our fakes
        assertTrue(result.initiated());
        assertEquals(1, metrics.count("continuum.handoff.failed"));
        assertEquals(0, coordinator.pendingCount());
    }
}
