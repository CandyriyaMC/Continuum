package lol.candyriya.continuum.api.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SessionInfoTest {

    private static SessionInfo sample() {
        return new SessionInfo(UUID.randomUUID(), "survival-1", SessionState.ACTIVE, 41,
                Instant.parse("2026-11-19T12:00:00Z"));
    }

    @Test
    void aWellFormedSessionValidates() {
        SessionInfo session = sample();

        assertEquals("survival-1", session.serverId());
        assertEquals(SessionState.ACTIVE, session.state());
        assertEquals(41, session.fencingToken());
        assertEquals(Instant.parse("2026-11-19T12:00:00Z"), session.since());
    }

    @Test
    void blankServerIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionInfo(UUID.randomUUID(), "", SessionState.ACTIVE, 1, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionInfo(UUID.randomUUID(), "   ", SessionState.ACTIVE, 1, Instant.now()));
    }

    @Test
    void nullServerIdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new SessionInfo(UUID.randomUUID(), null, SessionState.ACTIVE, 1, Instant.now()));
    }

    @Test
    void negativeFencingTokenIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionInfo(UUID.randomUUID(), "s", SessionState.ACTIVE, -1, Instant.now()));
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new SessionInfo(null, "s", SessionState.ACTIVE, 1, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new SessionInfo(UUID.randomUUID(), "s", null, 1, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new SessionInfo(UUID.randomUUID(), "s", SessionState.ACTIVE, 1, null));
    }

    @Test
    void allLifecycleStatesExist() {
        assertEquals(7, SessionState.values().length);
        assertNotNull(SessionState.CONNECTING);
        assertNotNull(SessionState.LOADING);
        assertNotNull(SessionState.ACTIVE);
        assertNotNull(SessionState.TRANSFERRING);
        assertNotNull(SessionState.SAVING);
        assertNotNull(SessionState.RELEASED);
        assertNotNull(SessionState.RECOVERY_REQUIRED);
    }
}
