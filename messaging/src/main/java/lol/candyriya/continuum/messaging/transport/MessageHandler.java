package lol.candyriya.continuum.messaging.transport;

/**
 * Contract for anything that consumes events from a {@link MessageTransport}.
 *
 * <p>The handler returns an explicit {@link HandlerVerdict} — silence is not
 * an option, the transport never guesses. Throwing is allowed (and common for
 * "database is down"-style failures); the transport catches everything,
 * maps it to {@link HandlerVerdict#NACK_REQUEUE} and routes it through the
 * normal retry decision table.</p>
 *
 * <p>What the transport guarantees in return:</p>
 * <ul>
 * <li>a thrown exception or a non-final verdict never leaves the message
 * unacknowledged forever — worst case it dead-letters after the budget</li>
 * <li>malformed frames and unknown event types are logged, acked and counted,
 * never requeued</li>
 * <li>deliveries are at-least-once; consumers that cannot tolerate repeats
 * must dedupe (see {@code IdempotentMessageHandler})</li>
 * </ul>
 */
@FunctionalInterface
public interface MessageHandler {

    HandlerVerdict handle(InboundMessage message) throws Exception;
}
