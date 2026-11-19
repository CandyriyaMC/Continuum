package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Proxy asks the backend to capture a player NOW — the proxy is about to
 * move this player off this backend and wants a durable pre-transfer
 * snapshot in place before it does.
 *
 * <p>The backend answers with a {@link CaptureResultMessage} reply carrying
 * the same correlation id; the proxy waits for it (with a bounded timeout)
 * before deciding whether the switch may proceed. This is the one
 * proxy-initiated request on the channel: unlike {@link RouteResultMessage}
 * &amp; friends, it does not answer a backend-originated frame — the proxy
 * fires it from the connection pipeline, unprompted by the backend.</p>
 *
 * <p>The {@code targetUuid} names the player to capture. The backend must
 * only capture the player whose connection the frame arrived on and must
 * cross-check this field against that connection — anything else is refused
 * with {@code captured=false}, never silently honored.</p>
 *
 * <p>{@code reason} is free-form context for logs ({@code "switch"} for a
 * proxy-driven server switch, {@code "transfer"} for a coordinated transfer,
 * or anything else). It is informational; a backend must not gate on it.</p>
 *
 * @param targetUuid uuid of the player to capture; must not be nil
 * @param reason     free-form context for logs; never empty-null, may be
 *                   empty, at most 256 characters
 */
public record CaptureRequestMessage(UUID targetUuid, String reason) implements ProxyToBackendMessage {

    public CaptureRequestMessage {
        Objects.requireNonNull(targetUuid, "targetUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null (use \"\" for no context)");
    }
}
