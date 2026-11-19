package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.UUID;

/**
 * A message that came off the wire, with the envelope data the payload record
 * deliberately does not carry.
 *
 * <p>The correlation id is envelope-level: it is never repeated inside the
 * JSON body, so a handler always gets exactly one source for it and there is
 * no way to read a request id out of a "reply" payload by accident.</p>
 *
 * @param message         the typed payload record for the frame's kind
 * @param correlationId   the in-flight correlation id; non-nil (decode rejects nil ids)
 * @param protocolVersion the frame's protocol version byte
 */
public record DecodedPluginMessage(PluginMessage message, UUID correlationId, int protocolVersion) {

    public DecodedPluginMessage {
        if (message == null) {
            throw new NullPointerException("message must not be null");
        }
        if (correlationId == null) {
            throw new NullPointerException("correlationId must not be null");
        }
    }
}
