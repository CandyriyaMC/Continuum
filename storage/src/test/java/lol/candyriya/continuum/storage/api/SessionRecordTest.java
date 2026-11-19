package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.session.SessionState;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRecordTest {

    private static SessionRecord record(SessionState state) {
        Instant now = Instant.now();
        return new SessionRecord(UUID.randomUUID(), 9L, "survival-1", 41L, state,
                now, now, now.plusSeconds(90), null);
    }

    @Test
    void ownedIsTrueForLiveStates() {
        for (SessionState state : SessionState.values()) {
            boolean expected = state != SessionState.RELEASED && state != SessionState.RECOVERY_REQUIRED;
            if (expected != record(state).owned()) {
                throw new AssertionError("owned() mismatch for " + state);
            }
        }
    }

    @Test
    void releasedSessionIsNotOwned() {
        assertFalse(record(SessionState.RELEASED).owned());
    }

    @Test
    void recoveryRequiredSessionIsNotOwned() {
        assertFalse(record(SessionState.RECOVERY_REQUIRED).owned());
    }

    @Test
    void sessionIdMustBePositive() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new SessionRecord(
                UUID.randomUUID(), 0, "s", 1L, SessionState.ACTIVE, now, now, now, null));
    }

    @Test
    void serverIdMustBeNonBlankAndBounded() {
        Instant now = Instant.now();
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new SessionRecord(
                player, 1, " ", 1L, SessionState.ACTIVE, now, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionRecord(
                player, 1, "x".repeat(65), 1L, SessionState.ACTIVE, now, now, now, null));
    }

    @Test
    void tokenMustNotBeNegative() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> new SessionRecord(
                UUID.randomUUID(), 1, "s", -1, SessionState.ACTIVE, now, now, now, null));
    }

    @Test
    void requiredFieldsMustNotBeNull() {
        Instant now = Instant.now();
        UUID player = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new SessionRecord(
                null, 1, "s", 1L, SessionState.ACTIVE, now, now, now, null));
        assertThrows(NullPointerException.class, () -> new SessionRecord(
                player, 1, "s", 1L, null, now, now, now, null));
        assertThrows(NullPointerException.class, () -> new SessionRecord(
                player, 1, "s", 1L, SessionState.ACTIVE, null, now, now, null));
        assertThrows(NullPointerException.class, () -> new SessionRecord(
                player, 1, "s", 1L, SessionState.ACTIVE, now, null, now, null));
        assertThrows(NullPointerException.class, () -> new SessionRecord(
                player, 1, "s", 1L, SessionState.ACTIVE, now, now, null, null));
    }

    @Test
    void endedAtMayBeNull() {
        record(SessionState.ACTIVE); // must not throw
    }
}
