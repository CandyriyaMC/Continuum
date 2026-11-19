package lol.candyriya.continuum.velocity.handoff;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteDecisionTest {

    private static final RegisteredServer HUB = HandoffFixtures.registeredServer("hub", 25565);
    private static final RegisteredServer SURVIVAL = HandoffFixtures.registeredServer("survival", 25566);
    private static final RegisteredServer WILDERNESS = HandoffFixtures.registeredServer("wilderness", 25567);

    private RouteDecision decision(List<RegisteredServer> servers) {
        return new RouteDecision(() -> servers);
    }

    @Test
    void knownRequestedServerIsUsed() {
        var verdict = decision(List.of(SURVIVAL, HUB)).decide("hub", "survival");
        assertTrue(verdict.allowed());
        assertEquals("hub", verdict.targetServer());
        assertEquals(RouteDecision.Outcome.ALLOWED, verdict.outcome());
    }

    @Test
    void unknownRequestedServerIsRefusedNotRemapped() {
        var verdict = decision(List.of(SURVIVAL, HUB)).decide("nope", "survival");
        assertFalse(verdict.allowed());
        assertNull(verdict.targetServer());
        assertEquals(RouteDecision.Outcome.DENIED, verdict.outcome());
        assertTrue(verdict.reason().contains("nope"));
    }

    @Test
    void requestedServerEqualToCurrentBackendIsRefused() {
        var verdict = decision(List.of(HUB)).decide("hub", "hub");
        assertFalse(verdict.allowed());
        assertEquals(RouteDecision.Outcome.DENIED, verdict.outcome());
        assertTrue(verdict.reason().contains("already connected"));
    }

    @Test
    void blankRequestFallsBackToSortedFirstConfiguredServer() {
        var verdict = decision(List.of(WILDERNESS, SURVIVAL, HUB)).decide(null, "wildcard");
        assertTrue(verdict.allowed());
        assertEquals("hub", verdict.targetServer());

        var blank = decision(List.of(WILDERNESS, SURVIVAL, HUB)).decide("   ", "wildcard");
        assertEquals("hub", blank.targetServer());
    }

    @Test
    void fallbackSkipsTheCurrentBackend() {
        var verdict = decision(List.of(HUB, SURVIVAL)).decide(null, "hub");
        assertTrue(verdict.allowed());
        assertEquals("survival", verdict.targetServer());
    }

    @Test
    void fallbackWithNoCandidatesIsUnavailable() {
        var verdict = decision(List.of(HUB)).decide(null, "hub");
        assertFalse(verdict.allowed());
        assertEquals(RouteDecision.Outcome.TARGET_UNAVAILABLE, verdict.outcome());
    }

    @Test
    void noServersConfiguredIsUnavailable() {
        var verdict = decision(List.of()).decide("hub", "hub");
        assertFalse(verdict.allowed());
        assertEquals(RouteDecision.Outcome.TARGET_UNAVAILABLE, verdict.outcome());
    }

    @Test
    void unknownCurrentBackendDoesNotBlockRouting() {
        var verdict = decision(List.of(HUB, SURVIVAL)).decide(null, null);
        assertTrue(verdict.allowed());
        assertEquals("hub", verdict.targetServer());
    }

    @Test
    void requestedServerIsTrimmed() {
        var verdict = decision(List.of(HUB)).decide("  hub  ", "survival");
        assertTrue(verdict.allowed());
        assertEquals("hub", verdict.targetServer());
    }

    @Test
    void throwingServerSupplierFailsClosed() {
        RouteDecision decision = new RouteDecision(() -> {
            throw new IllegalStateException("config blew up");
        });
        RouteDecision.Decision verdict = decision.decide("hub", null);
        assertFalse(verdict.allowed());
        assertEquals(RouteDecision.Outcome.TARGET_UNAVAILABLE, verdict.outcome());
    }

    @Test
    void nullServerListFailsClosed() {
        RouteDecision decision = new RouteDecision(() -> null);
        assertEquals(RouteDecision.Outcome.TARGET_UNAVAILABLE, decision.decide("hub", null).outcome());
    }

    @Test
    void allowedDecisionNamesTheTarget() {
        var verdict = decision(List.of(SURVIVAL)).decide("survival", "hub");
        assertTrue(verdict.allowed());
        assertEquals("survival", verdict.targetServer());
        assertEquals("", verdict.reason());
    }
}
