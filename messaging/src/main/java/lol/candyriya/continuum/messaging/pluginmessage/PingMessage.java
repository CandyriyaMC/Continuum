package lol.candyriya.continuum.messaging.pluginmessage;

import java.time.Instant;
import java.util.Objects;

/**
 * Liveness/latency probe from a backend to the proxy. The proxy answers with
 * a {@code continuum.pong} carrying this message's correlation id; {@code sentAt}
 * round-trips unchanged so the backend can compute an RTT without guessing.
 *
 * @param sentAt when the ping was emitted, epoch milli on the wire; must be
 *               within [1970-01-01T00:00:00Z, 2100-01-01T00:00:00Z)
 */
public record PingMessage(Instant sentAt) implements BackendToProxyMessage {

    public PingMessage {
        Objects.requireNonNull(sentAt, "sentAt must not be null");
    }
}
