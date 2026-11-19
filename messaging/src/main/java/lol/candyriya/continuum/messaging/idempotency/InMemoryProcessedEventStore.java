package lol.candyriya.continuum.messaging.idempotency;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference implementation of {@link ProcessedEventStore} — tests,
 * local development, demos. Unbounded and volatile: every restart forgets,
 * so duplicate side effects become possible across restarts. Production
 * consumers use the database-backed store wired by the storage layer
 * (unique index on {@code (consumer_group, event_id)}).
 */
public final class InMemoryProcessedEventStore implements ProcessedEventStore {

    // NUL separator: neither side can contain it, so no key-collision games
    private static final String SEP = "\u0000";

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isProcessed(String consumerGroup, UUID eventId) {
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        return seen.contains(key(consumerGroup, eventId));
    }

    @Override
    public boolean markProcessed(String consumerGroup, UUID eventId) {
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        return seen.add(key(consumerGroup, eventId));
    }

    private static String key(String group, UUID eventId) {
        return group + SEP + eventId;
    }
}
