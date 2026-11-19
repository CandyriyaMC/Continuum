package lol.candyriya.continuum.messaging.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryProcessedEventStoreTest {

    @Test
    void markIsFirstWriteWins() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        UUID id = UUID.randomUUID();
        assertTrue(store.markProcessed("group", id));
        assertFalse(store.markProcessed("group", id));
        assertTrue(store.isProcessed("group", id));
    }

    @Test
    void groupsAreIndependent() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        UUID id = UUID.randomUUID();
        store.markProcessed("group-a", id);
        assertTrue(store.isProcessed("group-a", id));
        assertFalse(store.isProcessed("group-b", id));
    }

    @Test
    void nullsAreRejected() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        assertThrows(NullPointerException.class, () -> store.isProcessed(null, UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> store.isProcessed("group", null));
        assertThrows(NullPointerException.class, () -> store.markProcessed(null, UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> store.markProcessed("group", null));
    }
}
