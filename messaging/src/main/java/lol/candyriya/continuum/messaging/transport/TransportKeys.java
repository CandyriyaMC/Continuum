package lol.candyriya.continuum.messaging.transport;

import java.util.regex.Pattern;

/**
 * Validation for the namespaced keys used on the wire. Routing keys and
 * binding keys are dotted lowercase topic patterns ({@code continuum.session.*},
 * {@code continuum.snapshot.#}); queue names are plain single-level tokens.
 * Both are validated before they reach the broker so a typo fails fast at the
 * call site instead of declaring garbage topology.
 */
public final class TransportKeys {

    /** A single topic segment or wildcard: {@code session}, {@code update-2}, {@code *}, {@code #}. */
    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[a-z0-9_-]+|\\*|#");

    /** Queue names: 1-255 chars, no dots required, no whitespace/control chars. */
    private static final Pattern QUEUE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    private TransportKeys() {
    }

    /**
     * Validates a topic pattern used as a publish routing key or a binding key.
     *
     * @return the pattern, unchanged
     * @throws IllegalArgumentException when the pattern is not a sane topic string
     */
    public static String requireValidBindingKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 255) {
            throw new IllegalArgumentException("binding key must be 1-255 chars, got: " + key);
        }
        for (String segment : key.split("\\.", -1)) {
            if (!TOPIC_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(
                        "binding key '" + key + "' has an invalid segment '" + segment
                                + "' (allowed: lowercase a-z, 0-9, '_', '-', '*', '#')");
            }
        }
        return key;
    }

    /**
     * Validates a queue name. Durable queue names are deployment constants —
     * they should name the consumer group, not the host ({@code velocity},
     * {@code backend-proxy-events}, ...).
     */
    public static String requireValidQueueName(String queueName) {
        if (queueName == null || !QUEUE_NAME.matcher(queueName).matches()) {
            throw new IllegalArgumentException(
                    "queue name must be 1-255 chars of [A-Za-z0-9._-], got: " + queueName);
        }
        return queueName;
    }

    /**
     * True when {@code pattern} can carry the given concrete routing key.
     * Only used by tests — the broker is the real arbiter — but handy for
     * sanity checks and diagnostics.
     */
    public static boolean matchesTopic(String pattern, String routingKey) {
        if (pattern == null || routingKey == null) {
            return false;
        }
        // broker semantics: * matches exactly one word, # matches zero or more words
        String[] p = pattern.split("\\.", -1);
        String[] k = routingKey.split("\\.", -1);
        return matchFrom(p, 0, k, 0);
    }

    private static boolean matchFrom(String[] p, int pi, String[] k, int ki) {
        if (pi == p.length) {
            return ki == k.length;
        }
        if ("#".equals(p[pi])) {
            if (pi == p.length - 1) {
                return true; // trailing # swallows the rest, even nothing
            }
            for (int skip = 0; ki + skip <= k.length; skip++) {
                if (matchFrom(p, pi + 1, k, ki + skip)) {
                    return true;
                }
            }
            return false;
        }
        if (ki == k.length) {
            return false;
        }
        if ("*".equals(p[pi]) || p[pi].equals(k[ki])) {
            return matchFrom(p, pi + 1, k, ki + 1);
        }
        return false;
    }
}
