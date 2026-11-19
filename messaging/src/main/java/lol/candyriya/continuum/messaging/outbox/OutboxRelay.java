package lol.candyriya.continuum.messaging.outbox;

import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.ProtocolException;
import lol.candyriya.continuum.messaging.transport.MessageTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The relay that drains the transactional outbox into the broker. The write
 * path (snapshot save, outbox row, same DB transaction) lives in the storage
 * layer; this class is the other half — claim, publish, confirm, mark.
 *
 * <p>Design rules:</p>
 * <ul>
 * <li>one poll loop per relay instance, single-threaded, on a virtual thread —
 * no overlap, a slow batch just delays the next tick</li>
 * <li>an event is marked published <b>only after</b> the broker confirmed its
 * frame; a crash anywhere before that leaves the row claimable and the event
 * goes out again — at-least-once, deduped downstream by event id</li>
 * <li>events whose envelope fails to decode are logged and marked failed -
 * the attempt budget eventually sets them aside; retrying them blindly
 * cannot help anyway</li>
 * <li>a failed publish is marked failed too - attempts increment, the row
 * stays claimable (or is set aside past the budget), and the next tick
 * drives it again</li>
 * <li>rows are re-bound to their registered payload type before publishing -
 * the local registry must know the event type or the row is marked failed
 * (an unknown type will never decode, so there is no point retrying)</li>
 * <li>a failing claimer never kills the loop; it logs and retries next tick</li>
 * </ul>
 *
 * <p>Not a queue: ordering is only as strong as the claimer's claim order
 * (insertion order for both SQL and Mongo implementations).</p>
 */
public final class OutboxRelay implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Hard per-frame budget; the transport should normally confirm in milliseconds. */
    private static final long PUBLISH_TIMEOUT_SECONDS = 30;

    private final OutboxClaimer claimer;
    private final MessageTransport transport;
    private final EnvelopeCodec codec;
    private final OutboxRelayConfig config;

    private final AtomicLong claimed = new AtomicLong();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong claimFailures = new AtomicLong();

    private volatile ScheduledExecutorService scheduler;

    public OutboxRelay(OutboxClaimer claimer, MessageTransport transport,
                       EnvelopeCodec codec, OutboxRelayConfig config) {
        this.claimer = Objects.requireNonNull(claimer, "claimer must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Starts the poll loop. Idempotent — a second call while running is a no-op. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("continuum-outbox-relay-", 0).factory());
        scheduler = service;
        service.scheduleWithFixedDelay(this::pollSafely, 100, config.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("outbox relay started (poll {}ms, batch {})", config.pollInterval().toMillis(), config.claimBatchSize());
    }

    private void pollSafely() {
        try {
            poll();
        } catch (Throwable t) {
            // never let the schedule die silently — a dead relay wedges the outbox
            log.error("outbox poll iteration blew up; continuing next tick", t);
        }
    }

    private void poll() {
        List<OutboxEvent> batch;
        try {
            batch = claimer.claimBatch(config.claimBatchSize());
        } catch (RuntimeException e) {
            claimFailures.incrementAndGet();
            log.error("outbox claim failed; will retry next tick", e);
            return;
        }
        if (batch == null || batch.isEmpty()) {
            return;
        }
        claimed.addAndGet(batch.size());

        List<UUID> acknowledged = new ArrayList<>(batch.size());
        List<UUID> failedIds = new ArrayList<>();
        for (OutboxEvent event : batch) {
            // re-bind the stored frame to its registered payload type so the
            // transport can re-encode and confirm it; the wire bytes survive
            // the round trip because encoding is deterministic
            MessageEnvelope<? extends EventPayload> bound;
            try {
                Class<? extends EventPayload> payloadType = codec.resolvePayloadType(event.eventType())
                        .orElseThrow(() -> new ProtocolException(
                                "event type '" + event.eventType() + "' is not registered in the local registry"));
                bound = codec.decode(event.envelope(), payloadType);
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                log.error("outbox event {} (type {}) cannot be decoded with the local registry - marking it failed",
                        event.eventId(), event.eventType(), e);
                failedIds.add(event.eventId());
                continue;
            }
            try {
                CompletableFuture<Void> future = transport.publish(bound).toCompletableFuture();
                future.orTimeout(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
                acknowledged.add(event.eventId());
            } catch (Exception e) {
                failed.incrementAndGet();
                Throwable root = e instanceof TimeoutException ? e : rootCause(e);
                log.warn("outbox event {} (type {}, attempt {}) failed to publish - leaving it for retry: {}",
                        event.eventId(), event.eventType(), event.attempts(), root.toString());
                failedIds.add(event.eventId());
            }
        }

        if (!failedIds.isEmpty()) {
            try {
                claimer.markFailed(failedIds, "publish did not complete");
            } catch (RuntimeException e) {
                // not fatal: the rows stay claimable (stale-claim reclaim) and the attempts bump is lost
                log.error("failed to mark {} outbox events failed - they will cycle again", failedIds.size(), e);
            }
        }

        if (acknowledged.isEmpty()) {
            return;
        }
        try {
            claimer.markPublished(acknowledged);
            published.addAndGet(acknowledged.size());
            log.debug("marked {} outbox events published", acknowledged.size());
        } catch (RuntimeException e) {
            // not fatal: the rows get reclaimed and republished; consumers dedupe on event id
            failed.addAndGet(acknowledged.size());
            log.error("failed to mark {} outbox events published — they will be republished",
                    acknowledged.size(), e);
        }
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public OutboxRelayStats stats() {
        return new OutboxRelayStats(claimed.get(), published.get(), failed.get(), claimFailures.get());
    }

    /** Stops the poll loop, waiting for an in-flight iteration to finish. */
    @Override
    public void close() {
        ScheduledExecutorService service = scheduler;
        if (service == null) {
            return;
        }
        scheduler = null;
        service.shutdown();
        try {
            if (!service.awaitTermination(config.closeAwait().toMillis(), TimeUnit.MILLISECONDS)) {
                service.shutdownNow();
                service.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("outbox relay stopped");
    }
}

