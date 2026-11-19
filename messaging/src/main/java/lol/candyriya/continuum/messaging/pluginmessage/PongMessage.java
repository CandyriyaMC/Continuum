package lol.candyriya.continuum.messaging.pluginmessage;

import java.time.Instant;
import java.util.Objects;

/**
 * The proxy's answer to a {@link PingMessage}. Carries the ping's correlation
 * id (at the envelope level) and echoes its {@code sentAt} so the backend can
 * measure RTT directly.
 *
 * @param sentAt the timestamp the original ping carried, echoed verbatim; must
 *               be within [1970-01-01T00:00:00Z, 2100-01-01T00:00:00Z)
 */
public record PongMessage(Instant sentAt) implements ProxyToBackendMessage {

    public PongMessage {
        Objects.requireNonNull(sentAt, "sentAt must not be null");
    }
}
