package lol.candyriya.continuum.messaging.rabbitmq;

import lol.candyriya.continuum.common.protocol.EnvelopeCodec;
import lol.candyriya.continuum.common.protocol.EventPayload;
import lol.candyriya.continuum.common.protocol.MessageEnvelope;
import lol.candyriya.continuum.common.protocol.ProtocolException;
import lol.candyriya.continuum.messaging.transport.ConfirmTimeoutException;
import lol.candyriya.continuum.messaging.transport.PublishException;
import lol.candyriya.continuum.messaging.transport.TransportStats;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Return;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Long-lived publisher with asynchronous confirm tracking. One instance per
 * transport; a single channel in confirm mode; no {@code waitForConfirms}
 * on the hot path — confirms are matched to outstanding futures by sequence
 * number.
 *
 * <p>Sequence discipline: the seq number is read, the outstanding entry goes
 * into the map, and only then does the frame go out — all under one monitor,
 * so a confirm can never arrive before we know about its entry. Multiple-acks
 * clear the whole head segment.</p>
 *
 * <p>Loss windows and what happens to them:</p>
 * <ul>
 * <li>channel/connection dies before a confirm arrives → the shutdown
 * listener fails the whole outstanding map; the outbox reclaims those rows
 * (they were never marked published) and republishes</li>
 * <li>confirm never arrives but the channel stays up → the sweeper fails
 * entries older than {@code confirmTimeout} the same way</li>
 * <li>broker negatively confirms → immediate exceptional completion</li>
 * </ul>
 */
final class Publisher {

    private static final Logger log = LoggerFactory.getLogger(Publisher.class);

    private static final long SWEEP_MIN_INTERVAL_MILLIS = 100;

    private final Channel channel;
    private final EnvelopeCodec codec;
    private final String exchange;
    private final TransportStats stats;
    private final Duration confirmTimeout;
    private final ConcurrentSkipListMap<Long, Outstanding> outstanding = new ConcurrentSkipListMap<>();
    private final ScheduledExecutorService sweeper;

    private record Outstanding(CompletableFuture<Void> future, long createdNanos,
                               String eventType, String eventId) {
    }

    private Publisher(Channel channel, EnvelopeCodec codec, String exchange,
                      TransportStats stats, Duration confirmTimeout) {
        this.channel = channel;
        this.codec = codec;
        this.exchange = exchange;
        this.stats = stats;
        this.confirmTimeout = confirmTimeout;
        long sweep = Math.max(SWEEP_MIN_INTERVAL_MILLIS, confirmTimeout.toMillis() / 2);
        this.sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("continuum-confirm-sweeper-", 0).factory());
        this.sweeper.scheduleWithFixedDelay(this::sweepSafely, sweep, sweep, TimeUnit.MILLISECONDS);
    }

    static Publisher open(Connection connection, EnvelopeCodec codec,
                          String exchange, TransportStats stats,
                          Duration confirmTimeout) throws IOException {
        Channel channel = connection.createChannel();
        channel.confirmSelect();
        Publisher publisher = new Publisher(channel, codec, exchange, stats, confirmTimeout);
        channel.addConfirmListener(publisher::onConfirmAck, publisher::onConfirmNack);
        // returns the listener; we keep the channel-side reference only, so no need to keep it
        channel.addReturnListener((com.rabbitmq.client.ReturnCallback) publisher::onReturn);
        // a channel that dies mid-confirm must not leave futures hanging; drain eagerly
        channel.addShutdownListener(signal -> publisher.drainOutstanding(
                "channel closed before the broker confirmed (" + signal.getMessage() + ")"));
        return publisher;
    }

    CompletionStage<Void> publish(MessageEnvelope<? extends EventPayload> envelope) {
        final byte[] body;
        try {
            body = codec.encode(envelope);
        } catch (ProtocolException e) {
            stats.incrementFailed();
            return CompletableFuture.failedFuture(
                    new PublishException("refusing to encode envelope for event type '"
                            + envelope.eventType() + "'", e));
        }

        String routingKey = envelope.eventType();
        Map<String, Object> headers = Map.of("protocol_version", envelope.protocolVersion());
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2) // persistent
                .messageId(envelope.eventId().toString())
                .correlationId(envelope.correlationId())
                .timestamp(java.util.Date.from(envelope.timestamp()))
                .type(envelope.eventType())
                .appId("continuum")
                .headers(headers)
                .build();

        CompletableFuture<Void> future = new CompletableFuture<>();
        Outstanding entry = new Outstanding(future, System.nanoTime(),
                envelope.eventType(), envelope.eventId().toString());

        synchronized (channel) {
            final long seq;
            try {
                seq = channel.getNextPublishSeqNo();
                outstanding.put(seq, entry);
                channel.basicPublish(exchange, routingKey, true, properties, body);
            } catch (IOException | RuntimeException e) {
                // the frame may or may not be beyond recall — drop the entry either way,
                // the outbox will drive the retry
                outstanding.values().removeIf(o -> o.future() == future);
                stats.incrementFailed();
                future.completeExceptionally(
                        new PublishException("broker rejected the publish frame for '" + routingKey + "'", e));
                return future;
            }
        }
        stats.incrementPublished();
        return future;
    }

    private void onConfirmAck(long seqNo, boolean multiple) throws IOException {
        List<Outstanding> confirmed = drain(seqNo, multiple);
        for (int i = 0; i < confirmed.size(); i++) {
            stats.incrementConfirmed();
        }
        for (Outstanding o : confirmed) {
            o.future().complete(null);
        }
    }

    private void onConfirmNack(long seqNo, boolean multiple) throws IOException {
        List<Outstanding> failed = drain(seqNo, multiple);
        for (int i = 0; i < failed.size(); i++) {
            stats.incrementFailed();
        }
        for (Outstanding o : failed) {
            o.future().completeExceptionally(new PublishException(
                    "broker negatively confirmed publish of '" + o.eventType() + "' (event " + o.eventId() + ")"));
        }
    }

    /** Removes the confirmed sequence(s) from the map; the caller completes the futures. */
    private List<Outstanding> drain(long seqNo, boolean multiple) {
        List<Outstanding> drained = new ArrayList<>();
        if (multiple) {
            for (Long key : outstanding.headMap(seqNo, true).keySet()) {
                Outstanding o = outstanding.remove(key);
                if (o != null) {
                    drained.add(o);
                }
            }
        } else {
            Outstanding single = outstanding.remove(seqNo);
            if (single != null) {
                drained.add(single);
            }
        }
        return drained;
    }

    private void onReturn(Return returned) {
        stats.incrementReturned();
        log.warn("broker returned an unroutable message: mandatory publish to exchange '{}' with routing key '{}' "
                        + "got replyCode={} ({}); the alternate exchange should normally catch these — check it exists. "
                        + "bodyBytes={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText(),
                returned.getBody() == null ? 0 : returned.getBody().length);
    }

    /** Fails every outstanding publish. Safe to call twice; idempotent by inspection. */
    private int drainOutstanding(String reason) {
        List<Outstanding> drained = new ArrayList<>();
        outstanding.forEach((seq, entry) -> {
            if (outstanding.remove(seq, entry)) {
                drained.add(entry);
            }
        });
        if (drained.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < drained.size(); i++) {
            stats.incrementFailed();
        }
        log.warn("{} — failing {} outstanding publish(es); the outbox will republish", reason, drained.size());
        PublishException failure = new PublishException(reason);
        for (Outstanding o : drained) {
            o.future().completeExceptionally(failure);
        }
        return drained.size();
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (Throwable t) {
            log.error("confirm sweeper iteration failed; continuing", t);
        }
    }

    private void sweep() {
        long cutoffNanos = System.nanoTime() - confirmTimeout.toNanos();
        List<Outstanding> expired = new ArrayList<>();
        outstanding.entrySet().removeIf(e -> {
            boolean stale = e.getValue().createdNanos() < cutoffNanos;
            if (stale) {
                expired.add(e.getValue());
            }
            return stale;
        });
        if (expired.isEmpty()) {
            return;
        }
        for (int i = 0; i < expired.size(); i++) {
            stats.incrementFailed();
        }
        for (Outstanding o : expired) {
            o.future().completeExceptionally(new ConfirmTimeoutException(
                    "no broker confirmation within " + confirmTimeout.toMillis() + "ms for '"
                            + o.eventType() + "' (event " + o.eventId() + ")"));
        }
    }

    /** Fails outstanding entries, stops the sweeper, closes the channel. */
    void close() {
        sweeper.shutdownNow();
        drainOutstanding("continuum transport closed");
        try {
            channel.close();
        } catch (IOException | java.util.concurrent.TimeoutException | RuntimeException e) {
            log.warn("failed to close publisher channel cleanly: {}", e.toString());
        }
    }
}
