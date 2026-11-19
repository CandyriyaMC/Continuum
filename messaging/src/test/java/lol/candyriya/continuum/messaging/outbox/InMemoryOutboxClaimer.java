package lol.candyriya.continuum.messaging.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * Test-double claimer with honest bookkeeping: claims move rows out of the
 * pending list, marks record them. Not thread-naive - the relay polls from
 * its own thread while tests assert from the test thread.
 */
public final class InMemoryOutboxClaimer implements OutboxClaimer {

    private final List<OutboxEvent> pending = new CopyOnWriteArrayList<>();
    private final List<UUID> published = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> failedReasons = new ConcurrentHashMap<>();
    private final AtomicInteger claimCalls = new AtomicInteger();
    private volatile RuntimeException claimFailure;

    public void add(OutboxEvent event) {
        pending.add(event);
    }

    public void failClaims(RuntimeException failure) {
        this.claimFailure = failure;
    }

    public int claimCallCount() {
        return claimCalls.get();
    }

    /** Ids passed to markPublished since claim order is insertion order. */
    public List<UUID> publishedEventIds() {
        return List.copyOf(published);
    }

    public List<UUID> failedEventIds() {
        return List.copyOf(failedReasons.keySet());
    }

    public String failureReasonFor(UUID eventId) {
        return failedReasons.get(eventId);
    }

    public int pendingCount() {
        return pending.size();
    }

    @Override
    public List<OutboxEvent> claimBatch(int limit) {
        claimCalls.incrementAndGet();
        RuntimeException failure = claimFailure;
        if (failure != null) {
            throw failure;
        }
        synchronized (pending) {
            int take = Math.min(limit, pending.size());
            List<OutboxEvent> batch = new ArrayList<>(take);
            for (int i = 0; i < take; i++) {
                batch.add(pending.remove(0));
            }
            return batch;
        }
    }

    @Override
    public void markPublished(java.util.Collection<UUID> eventIds) {
        published.addAll(eventIds);
    }

    @Override
    public void markFailed(java.util.Collection<UUID> eventIds, String reason) {
        for (UUID id : eventIds) {
            failedReasons.put(id, reason);
        }
    }
}
