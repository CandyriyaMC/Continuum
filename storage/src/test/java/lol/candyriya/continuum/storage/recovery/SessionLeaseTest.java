package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.storage.api.SessionRecord;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lease heartbeat: renewal semantics, deregistration, graceful stop.
 */
class SessionLeaseTest {

    @Test
    void registeredSessionsGetTheirLeasesRenewed() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage(Duration.ofSeconds(5));
        UUID player = UUID.randomUUID();
        var session = storage.acquireSession(player, "survival-1");

        SessionLease lease = new SessionLease(storage, Duration.ofMillis(50));
        lease.register(player, session.token());

        Thread.sleep(20); // make the clock advance so the renewal is visible
        assertEquals(1, lease.renewOnce());
        SessionRecord renewed = storage.getSession(player).orElseThrow();
        assertTrue(renewed.lastHeartbeat().isAfter(session.lastHeartbeat()),
                "the renewal must push the heartbeat forward");
        assertTrue(renewed.leaseExpiresAt().isAfter(session.leaseExpiresAt()),
                "the renewal must push the lease forward");
        assertEquals(1, lease.registered());
    }

    @Test
    void unregisteredSessionsAreNoLongerRenewed() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        var session = storage.acquireSession(player, "survival-2");

        SessionLease lease = new SessionLease(storage, Duration.ofSeconds(60));
        lease.register(player, session.token());
        lease.unregister(player);

        assertEquals(0, lease.registered());
        assertEquals(0, lease.renewOnce());
    }

    @Test
    void losingTheLeaseDropsTheRegistration() {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        UUID player = UUID.randomUUID();
        var session = storage.acquireSession(player, "survival-3");

        SessionLease lease = new SessionLease(storage, Duration.ofSeconds(60));
        lease.register(player, session.token());
        // the backend lost ownership elsewhere: release from "another node"
        storage.releaseSession(player, session.token());

        assertEquals(0, lease.renewOnce(), "a released session cannot be renewed");
        assertEquals(0, lease.registered(), "the registration must be dropped, not renewed forever");
    }

    @Test
    void startAndCloseStopTheLoop() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage();
        SessionLease lease = new SessionLease(storage, Duration.ofMillis(50));
        lease.start();
        assertTrue(lease.running());
        lease.start(); // idempotent
        lease.close();
        assertFalse(lease.running());
        lease.close(); // idempotent
    }

    @Test
    void theLoopRenewsInTheBackground() throws Exception {
        InMemoryPlayerStorage storage = new InMemoryPlayerStorage(Duration.ofSeconds(30));
        UUID player = UUID.randomUUID();
        var session = storage.acquireSession(player, "survival-4");

        SessionLease lease = new SessionLease(storage, Duration.ofMillis(50));
        lease.register(player, session.token());
        try {
            lease.start();
            Instant deadline = Instant.now().plusSeconds(3);
            boolean renewed = false;
            while (Instant.now().isBefore(deadline)) {
                if (storage.getSession(player).orElseThrow().lastHeartbeat().isAfter(session.lastHeartbeat())) {
                    renewed = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(renewed, "the background loop must renew the lease without explicit ticks");
        } finally {
            lease.close();
        }
    }
}
