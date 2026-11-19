package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.common.i18n.TypedArgument;

import net.kyori.adventure.text.Component;

import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;

/**
 * Renders backend messages to Adventure components with the locale chain
 * resolved per player.
 *
 * <p>Chain: an explicit requested locale, else the client locale when
 * {@code i18n.use-client-locale} is on, else the configured default — the
 * fallback chain (requested → {@code i18n.fallback-locale} → {@code en_US}) is
 * applied inside the translator. The backend treats the configured locale as
 * the ceiling: when Velocity starts forwarding a decided per-player locale it
 * will arrive as the requested locale, and nothing else changes.</p>
 */
public final class PaperMessages {

    private final Translator translator;
    private final I18nConfig i18n;

    public PaperMessages(Translator translator, I18nConfig i18n) {
        this.translator = translator;
        this.i18n = i18n;
    }

    /** Renders with the configured default locale. */
    public Component render(String key, TypedArgument... args) {
        return translator.render(key, args);
    }

    /** Renders with an explicit requested locale (null = configured default), applying the fallback chain. */
    public Component render(@Nullable String requestedLocale, String key, TypedArgument... args) {
        return translator.render(requestedLocale, key, args);
    }

    /**
     * Renders for a player: client locale when enabled and known, otherwise the configured default,
     * always with the fallback chain behind it.
     */
    public Component renderForPlayer(Player player, String key, TypedArgument... args) {
        String requested = null;
        if (i18n.useClientLocale() && player != null) {
            String clientLocale = player.getLocale();
            if (clientLocale != null && !clientLocale.isBlank()) {
                requested = Translator.normalizeLocale(clientLocale);
            }
        }
        return translator.render(requested, key, args);
    }

    /** The locales the bundled catalogs can serve, en_US first. */
    public java.util.Set<String> supportedLocales() {
        return translator.supportedLocales();
    }
}
