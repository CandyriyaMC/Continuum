package lol.candyriya.continuum.common.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural validation for envelopes. Split into a collect-all variant
 * (tests, diagnostics) and a fail-fast variant (hot path).
 */
public final class EnvelopeValidator {

    /** How far in the future a timestamp may be before we call the clock (or the sender) broken. */
    public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /** Hard cap for the correlation id; it is for tracing, not for smuggling data. */
    public static final int MAX_CORRELATION_ID_LENGTH = 128;

    private EnvelopeValidator() {
    }

    /** All violations, empty when valid. */
    public static List<String> validate(MessageEnvelope<?> envelope) {
        List<String> problems = new ArrayList<>();
        if (!Protocol.isSupported(envelope.protocolVersion())) {
            problems.add("protocol_version: " + envelope.protocolVersion()
                    + " is not supported by this build (min " + Protocol.MIN_SUPPORTED_VERSION
                    + ", max " + Protocol.CURRENT_VERSION + ")");
        }
        if (!MessageTypeRegistry.EVENT_TYPE_PATTERN.matcher(envelope.eventType()).matches()) {
            problems.add("event_type: '" + envelope.eventType()
                    + "' must be dotted lowercase segments like 'continuum.transfer.requested'");
        }
        if (envelope.correlationId().length() > MAX_CORRELATION_ID_LENGTH) {
            problems.add("correlation_id: exceeds " + MAX_CORRELATION_ID_LENGTH + " characters");
        }
        if (envelope.timestamp().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            problems.add("timestamp: is more than " + MAX_CLOCK_SKEW.toMinutes() + " minutes in the future");
        }
        return List.copyOf(problems);
    }

    /** Throws {@link ProtocolException} on the first validation problem found. */
    public static void requireValid(MessageEnvelope<?> envelope) {
        List<String> problems = validate(envelope);
        if (!problems.isEmpty()) {
            throw new ProtocolException(String.join("; ", problems));
        }
    }

    /** Size guard for a raw frame. Returns null when ok, violation string otherwise. */
    public static String checkSize(int sizeBytes, int maxBytes) {
        return sizeBytes > maxBytes ? "frame is " + sizeBytes + " bytes, limit is " + maxBytes : null;
    }
}
