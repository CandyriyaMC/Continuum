package lol.candyriya.continuum.common.protocol;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Known message types and the payload class that goes with each.
 *
 * <p>Deliberately strict: consumers decode only registered types, so a typo'd
 * or spoofed {@code eventType} on the wire fails closed instead of landing in
 * a wild {@code Object} handler. Register types at plugin bootstrap; the
 * registry itself is thread-safe.</p>
 */
public final class MessageTypeRegistry {

    /** Dotted lowercase segments; at least one dot. */
    public static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+$");

    private final Map<String, Class<? extends EventPayload>> payloadTypes = new ConcurrentHashMap<>();

    /**
     * Registers {@code payloadType} for {@code eventType}.
     *
     * @throws IllegalArgumentException on bad event type syntax or a duplicate registration
     */
    public MessageTypeRegistry register(String eventType, Class<? extends EventPayload> payloadType) {
        if (!EVENT_TYPE_PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "eventType '" + eventType + "' must be dotted lowercase segments like 'continuum.transfer.requested'");
        }
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType must not be null");
        }
        Class<? extends EventPayload> previous = payloadTypes.putIfAbsent(eventType, payloadType);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "eventType '" + eventType + "' already registered to " + previous.getName());
        }
        return this;
    }

    /** @return the payload class registered for {@code eventType}, if any */
    public Optional<Class<? extends EventPayload>> resolve(String eventType) {
        return Optional.ofNullable(payloadTypes.get(eventType));
    }

    public boolean isRegistered(String eventType) {
        return payloadTypes.containsKey(eventType);
    }

    /** All registered event types. Unmodifiable snapshot. */
    public Set<String> registeredTypes() {
        return Set.copyOf(payloadTypes.keySet());
    }
}
