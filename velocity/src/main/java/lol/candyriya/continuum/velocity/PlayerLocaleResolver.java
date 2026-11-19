package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;

import com.velocitypowered.api.proxy.Player;

import org.slf4j.Logger;

import java.util.Locale;

/**
 * Resolves which locale a player should see messages in.
 *
 * <p>Chain, first hit wins:</p>
 * <ol>
 *     <li>the locale the player picked in their Continuum settings
 *         ({@link PlayerLocaleSource}; storage-backed once that lands)</li>
 *     <li>the client's language from its settings packet, when the deployment
 *         honors it ({@code i18n.use-client-locale})</li>
 *     <li>the proxy-wide default from the shared config ({@code i18n.default-locale})</li>
 *     <li>{@code en_US}, always</li>
 * </ol>
 *
 * <p>Every lookup is defensive: a preference store that throws degrades the
 * chain to the next step instead of breaking message rendering.</p>
 */
public final class PlayerLocaleResolver {

    private final PlayerLocaleSource source;
    private final Logger logger;

    public PlayerLocaleResolver(PlayerLocaleSource source, Logger logger) {
        this.source = source;
        this.logger = logger;
    }

    public String resolve(Player player, I18nConfig config) {
        try {
            var preferred = source.preferredLocale(player.getUniqueId()).map(PlayerLocaleResolver::normalize);
            if (preferred.isPresent() && preferred.get() != null) {
                return preferred.get();
            }
        } catch (Exception e) {
            // a flaky preference store must not take message rendering down with it
            logger.warn("player locale preference lookup failed for {}; falling back to client locale", player.getUniqueId(), e);
        }

        if (config.useClientLocale()) {
            Locale client = player.getEffectiveLocale();
            String tag = normalize(client == null ? null : client.toString());
            if (tag != null) {
                return tag;
            }
        }

        String configured = normalize(config.defaultLocale());
        if (configured != null) {
            return configured;
        }
        return Translator.HARD_FALLBACK_LOCALE;
    }

    /** Normalizes loose input ({@code en-us}, {@code EN_us}); null/blank becomes null. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Translator.normalizeLocale(raw);
    }
}
