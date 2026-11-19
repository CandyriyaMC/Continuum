package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Proxy's routing decision for a handoff request — the reply to
 * {@link HandoffRequestMessage}.
 *
 * @param player       uuid of the player the decision is about; must not be nil
 * @param outcome      what the proxy decided; must not be null
 * @param targetServer name of the server the player will land on; required
 *                     (non-blank, same rules as a target server name) when
 *                     {@code outcome} is {@link RouteOutcome#ALLOWED}, may be
 *                     empty otherwise
 * @param reason       free-form context for logs and operators; may be empty,
 *                     at most 256 characters
 */
public record RouteResultMessage(UUID player, RouteOutcome outcome, String targetServer, String reason)
        implements ProxyToBackendMessage {

    public RouteResultMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(targetServer, "targetServer must not be null (use \"\" when not ALLOWED)");
        Objects.requireNonNull(reason, "reason must not be null (use \"\" for no context)");
    }
}
