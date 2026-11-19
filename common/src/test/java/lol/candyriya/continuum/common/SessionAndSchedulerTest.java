package lol.candyriya.continuum.common.session;

import lol.candyriya.continuum.common.scheduler.DirectScheduler;
import lol.candyriya.continuum.common.scheduler.EntityRef;
import lol.candyriya.continuum.common.scheduler.PlatformScheduler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SessionAndSchedulerTest {

    @Test
    void sessionStateEnumCoversTheDocumentedLifecycle() {
        assertArrayEquals(
                new String[]{"CONNECTING", "LOADING", "ACTIVE", "TRANSFERRING", "SAVING", "RELEASED", "RECOVERY_REQUIRED"},
                java.util.Arrays.stream(SessionState.values()).map(Enum::name).toArray(String[]::new));
        // valueOf roundtrip: enum names are a storage contract
        assertEquals(SessionState.ACTIVE, SessionState.valueOf("ACTIVE"));
    }

    @Test
    void transferStateEnumCoversTheDocumentedLifecycle() {
        assertArrayEquals(
                new String[]{"CREATED", "CAPTURING", "SNAPSHOT_SAVED", "HANDOFF_REQUESTED", "CONNECTING",
                        "RESTORING", "ACTIVE", "COMMITTED", "FAILED", "RECOVERY_REQUIRED"},
                java.util.Arrays.stream(TransferState.values()).map(Enum::name).toArray(String[]::new));
        assertEquals(TransferState.COMMITTED, TransferState.valueOf("COMMITTED"));
    }

    @Test
    void directSchedulerRunsInlineInOrder() {
        PlatformScheduler scheduler = new DirectScheduler();
        List<String> order = new ArrayList<>();
        AtomicBoolean entityRan = new AtomicBoolean();

        scheduler.global(() -> order.add("global"));
        scheduler.entity(new EntityRef(UUID.randomUUID()), () -> {
            order.add("entity");
            entityRan.set(true);
        });
        scheduler.async(() -> order.add("async"));

        assertEquals(List.of("global", "entity", "async"), order);
        assertTrue(entityRan.get());
    }

    @Test
    void entityRefRejectsNullId() {
        assertThrows(NullPointerException.class, () -> new EntityRef(null));
    }
}
