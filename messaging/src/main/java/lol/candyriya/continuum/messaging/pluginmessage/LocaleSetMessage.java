package lol.candyriya.continuum.messaging.pluginmessage;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend reports the locale for a player's session. The proxy is the
 * central point for locale decisions; the backend with the player attached
 * informs it whenever the effective locale changes.
 *
 * @param player uuid of the player; must not be nil
 * @param locale BCP 47-ish language tag, at most 35 characters, e.g.
 *               {@code en_US}, {@code ru}, {@code zh-CN}, {@code kk-Cyrl}
 */
public record LocaleSetMessage(UUID player, String locale) implements BackendToProxyMessage {

    public LocaleSetMessage {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
    }
}
