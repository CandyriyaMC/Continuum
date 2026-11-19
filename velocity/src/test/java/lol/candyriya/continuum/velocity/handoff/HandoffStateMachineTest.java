package lol.candyriya.continuum.velocity.handoff;

import lol.candyriya.continuum.common.session.TransferState;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffStateMachineTest {

    @Test
    void everyLegalEdgeIsAccepted() {
        assertAllLegal(TransferState.CREATED, TransferState.HANDOFF_REQUESTED, TransferState.CONNECTING, TransferState.FAILED);
        assertAllLegal(TransferState.HANDOFF_REQUESTED, TransferState.CONNECTING, TransferState.FAILED);
        assertAllLegal(TransferState.CONNECTING, TransferState.RESTORING, TransferState.FAILED);
        assertAllLegal(TransferState.RESTORING, TransferState.ACTIVE, TransferState.FAILED, TransferState.RECOVERY_REQUIRED);
        assertAllLegal(TransferState.ACTIVE, TransferState.COMMITTED);
    }

    private static void assertAllLegal(TransferState from, TransferState... to) {
        assertEquals(Set.of(to), legalEdgesFrom(from), "edges from " + from);
        for (TransferState target : to) {
            assertTrue(HandoffStateMachine.can(from, target));
            assertEquals(target, HandoffStateMachine.require(from, target));
        }
    }

    private static Set<TransferState> legalEdgesFrom(TransferState from) {
        EnumSet<TransferState> edges = EnumSet.noneOf(TransferState.class);
        for (TransferState to : TransferState.values()) {
            if (HandoffStateMachine.can(from, to)) {
                edges.add(to);
            }
        }
        return edges;
    }

    @Test
    void terminalStatesAdmitNoTransitions() {
        for (TransferState terminal : EnumSet.of(TransferState.COMMITTED, TransferState.FAILED, TransferState.RECOVERY_REQUIRED)) {
            assertTrue(HandoffStateMachine.isTerminal(terminal));
            assertTrue(legalEdgesFrom(terminal).isEmpty(), terminal + " must be terminal");
            assertThrows(IllegalHandoffTransitionException.class,
                    () -> HandoffStateMachine.require(terminal, TransferState.CONNECTING));
        }
    }

    @Test
    void nonTerminalStatesAreNotTerminal() {
        for (TransferState state : TransferState.values()) {
            if (!EnumSet.of(TransferState.COMMITTED, TransferState.FAILED, TransferState.RECOVERY_REQUIRED).contains(state)) {
                assertFalse(HandoffStateMachine.isTerminal(state));
            }
        }
    }

    @Test
    void illegalEdgesAreRejected() {
        assertIllegal(TransferState.CREATED, TransferState.RESTORING);
        assertIllegal(TransferState.CREATED, TransferState.COMMITTED);
        assertIllegal(TransferState.CREATED, TransferState.RECOVERY_REQUIRED);
        assertIllegal(TransferState.HANDOFF_REQUESTED, TransferState.ACTIVE);
        assertIllegal(TransferState.HANDOFF_REQUESTED, TransferState.RESTORING);
        assertIllegal(TransferState.CONNECTING, TransferState.COMMITTED);
        assertIllegal(TransferState.CONNECTING, TransferState.RECOVERY_REQUIRED);
        assertIllegal(TransferState.RESTORING, TransferState.CONNECTING);
        assertIllegal(TransferState.RESTORING, TransferState.COMMITTED);
        assertIllegal(TransferState.ACTIVE, TransferState.RESTORING);
        assertIllegal(TransferState.ACTIVE, TransferState.FAILED);
    }

    private static void assertIllegal(TransferState from, TransferState to) {
        assertFalse(HandoffStateMachine.can(from, to));
        IllegalHandoffTransitionException exception = assertThrows(IllegalHandoffTransitionException.class,
                () -> HandoffStateMachine.require(from, to));
        assertTrue(exception.getMessage().contains(from.name()));
        assertTrue(exception.getMessage().contains(to.name()));
    }

    @Test
    void nullsAreNotTransitions() {
        assertFalse(HandoffStateMachine.can(null, TransferState.CONNECTING));
        assertFalse(HandoffStateMachine.can(TransferState.CONNECTING, null));
        assertFalse(HandoffStateMachine.isTerminal(null));
    }

    @Test
    void pendingRecordBumpsUpdatedAtAndEnforcesTheMachine() {
        PendingHandoff created = new PendingHandoff(java.util.UUID.randomUUID(), "player", "hub-1", "hub-2",
                "switch", TransferState.CREATED, java.time.Instant.parse("2026-11-01T00:00:00Z"),
                java.time.Instant.parse("2026-11-01T00:00:00Z"));
        PendingHandoff connecting = created.transitionTo(TransferState.CONNECTING);
        assertEquals(TransferState.CONNECTING, connecting.phase());
        assertEquals(created.createdAt(), connecting.createdAt());
        // a skipped intermediate phase is refused even at the record level
        assertThrows(IllegalHandoffTransitionException.class,
                () -> connecting.transitionTo(TransferState.ACTIVE));
    }
}
