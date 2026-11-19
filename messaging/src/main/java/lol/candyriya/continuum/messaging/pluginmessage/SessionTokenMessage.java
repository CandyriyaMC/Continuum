package lol.candyriya.continuum.messaging.pluginmessage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Proxy delivers the session token for a player whose handoff the target
 * backend just accepted with a {@link HandoffReadyMessage}. The correlation
 * id of the reply matches that readiness report — not the original handoff
 * request — so the backend can pair them independently.
 *
 * @param player    uuid of the player the token is for; must not be nil
 * @param token     opaque session token; non-blank, printable ASCII, at most
 *                  512 characters
 * @param expiresAt when the token stops being valid; must be within the range
 *                  [1970-01-01, 2100-01-01)
 */
public record SessionTokenMessage(UUID player, String token, Instant expiresAt)
        implements ProxyToBackendMessage {

    public SessionTokenMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
