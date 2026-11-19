package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.common.i18n.TypedArgument;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import net.kyori.adventure.text.Component;

import java.util.function.Supplier;

/**
 * Key-to-Component helpers for the proxy.
 *
 * <p>Wraps the shared {@link Translator} so call sites never juggle locale
 * resolution or prefixing by hand. Every command the proxy emits goes through
 * here: string building stays in the locale catalogs, markup stays MiniMessage,
 * and player preferences get honored when they exist.</p>
 *
 * <p>The translator and config are read through suppliers because both are
 * hot-swappable by the reload command - this object is long-lived, the
 * instances it points at are not.</p>
 */
public final class VelocityMessages {

    private final Supplier<Translator> translator;
    private final Supplier<I18nConfig> config;
    private final PlayerLocaleResolver locales;

    public VelocityMessages(Supplier<Translator> translator, Supplier<I18nConfig> config, PlayerLocaleResolver locales) {
        this.translator = translator;
        this.config = config;
        this.locales = locales;
    }

    /** Renders {@code key} for an explicit locale tag. */
    public Component render(String locale, String key, TypedArgument... args) {
        return translator.get().render(locale, key, args);
    }

    /** Renders {@code key} in the proxy's configured default locale. */
    public Component renderDefault(String key, TypedArgument... args) {
        return translator.get().render(defaultLocaleTag(), key, args);
    }

    /** Wraps a pre-built document with the Continuum prefix once. */
    public Component prefixed(Component body) {
        return Component.empty().append(prefix()).append(body);
    }

    /**
     * Sends a prefixed message to the source. Players get it in their resolved
     * locale; anything else (console, RCON) gets the proxy default.
     */
    public void send(CommandSource source, String key, TypedArgument... args) {
        String locale = localeFor(source);
        source.sendMessage(prefixed(translator.get().render(locale, key, args)));
    }

    /** The locale tag to use for a command source (players: resolved chain, otherwise: default). */
    public String localeFor(CommandSource source) {
        if (source instanceof Player player) {
            return locales.resolve(player, config.get());
        }
        return defaultLocaleTag();
    }

    /** The proxy's default locale tag, always normalized, falling back to en_US. */
    public String defaultLocaleTag() {
        String tag = PlayerLocaleResolver.normalize(config.get().defaultLocale());
        return tag != null ? tag : Translator.HARD_FALLBACK_LOCALE;
    }

    private Component prefix() {
        return translator.get().render(defaultLocaleTag(), "velocity.prefix");
    }
}
