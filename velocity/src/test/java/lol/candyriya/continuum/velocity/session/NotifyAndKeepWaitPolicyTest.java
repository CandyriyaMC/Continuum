package lol.candyriya.continuum.velocity.session;

import lol.candyriya.continuum.velocity.Metrics;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.kyori.adventure.text.Component;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyAndKeepWaitPolicyTest {

    private final Logger logger = LoggerFactory.getLogger(NotifyAndKeepWaitPolicyTest.class);

    private NetworkSessionRegistry newRegistry() {
        return new NetworkSessionRegistry(logger, Metrics.NOOP, 10);
    }

    @Test
    void kickMarksPlayerAsAwaitingBackend() {
        var registry = newRegistry();
        var policy = new NotifyAndKeepWaitPolicy(registry, logger);
        UUID player = UUID.randomUUID();

        policy.reconcile(new KickContext(player, "survival-1", Component.text("spamming"), Instant.now()));

        assertEquals(1, registry.awaitingBackendCount());
        var snapshot = registry.snapshot(player);
        assertTrue(snapshot.isPresent());
        assertTrue(snapshot.get().awaitingBackend());
        assertEquals("survival-1", snapshot.get().backendName());
        assertEquals("spamming", snapshot.get().awaitingReason());
    }

    @Test
    void reconcileIsIdempotent() {
        var registry = newRegistry();
        var policy = new NotifyAndKeepWaitPolicy(registry, logger);
        UUID player = UUID.randomUUID();
        var context = new KickContext(player, "survival-2", Component.text("botting"), Instant.now());

        policy.reconcile(context);
        policy.reconcile(context);

        assertEquals(1, registry.awaitingBackendCount());
    }

    @Test
    void kickWithoutReasonStillMarksAwaiting() {
        var registry = newRegistry();
        var policy = new NotifyAndKeepWaitPolicy(registry, logger);
        UUID player = UUID.randomUUID();

        policy.reconcile(new KickContext(player, "lobby", null, Instant.now()));

        var snapshot = registry.snapshot(player).orElseThrow();
        assertTrue(snapshot.awaitingBackend());
        assertEquals("<no reason given>", snapshot.awaitingReason());
    }
}
