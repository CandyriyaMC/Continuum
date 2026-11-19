package lol.candyriya.continuum.common.i18n;

import lol.candyriya.continuum.common.config.I18nConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Renders message keys against per-locale catalogs with a fallback chain:
 * requested locale → configured fallback locale → bundled en_US.
 *
 * <p>Catalogs are {@code locales/<locale>.yml} (same format on every
 * platform); every key must exist at least in en_US. Missing keys render as a
 * visible red placeholder and log a warning — a missing translation should
 * fail loud in review, never silently message players garbage.</p>
 */
public final class Translator {

    /** Last-resort locale; always required. */
    public static final String HARD_FALLBACK_LOCALE = "en_US";

    private static final List<String> BUNDLED = List.of("en_US", "ru_RU", "de_DE", "kk_KZ", "zh_CN");

    private static final Logger LOGGER = LoggerFactory.getLogger(Translator.class);

    private final Map<String, MessageCatalog> catalogs;
    private final String defaultLocale;
    private final String fallbackLocale;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private Translator(Map<String, MessageCatalog> catalogs, String defaultLocale, String fallbackLocale) {
        this.catalogs = Map.copyOf(catalogs);
        this.defaultLocale = normalizeLocale(defaultLocale);
        this.fallbackLocale = normalizeLocale(fallbackLocale);
        if (!this.catalogs.containsKey(HARD_FALLBACK_LOCALE)) {
            throw new I18nException("catalog set is missing the mandatory " + HARD_FALLBACK_LOCALE);
        }
    }

    /** Builds a translator from already-loaded catalogs (mostly tests and reload flows). */
    public static Translator of(Map<String, MessageCatalog> catalogs, String defaultLocale, String fallbackLocale) {
        return new Translator(catalogs, defaultLocale, fallbackLocale);
    }

    /**
     * Loads every {@code *.yml} in {@code localesDir}.
     *
     * @throws I18nException when the directory is missing/unreadable, contains no
     *                       catalogs, or none of them is en_US
     */
    public static Translator load(Path localesDir, I18nConfig config) {
        if (!Files.isDirectory(localesDir)) {
            throw new I18nException("locales directory does not exist or is not a directory: " + localesDir);
        }
        var catalogs = new java.util.LinkedHashMap<String, MessageCatalog>();
        try (Stream<Path> files = Files.list(localesDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(p -> {
                        MessageCatalog catalog = MessageCatalog.load(p);
                        catalogs.put(catalog.locale(), catalog);
                    });
        } catch (IOException e) {
            throw new I18nException("cannot list locales directory " + localesDir, e);
        }
        if (catalogs.isEmpty()) {
            throw new I18nException("no locale catalogs (*.yml) found in " + localesDir);
        }
        return new Translator(catalogs, config.defaultLocale(), config.fallbackLocale());
    }

    /**
     * Loads the catalogs bundled into this jar (classpath {@code /locales/}).
     * All non-en_US catalogs are optional at runtime; en_US is mandatory.
     */
    public static Translator loadBundled(ClassLoader classLoader, I18nConfig config) {
        var catalogs = new java.util.LinkedHashMap<String, MessageCatalog>();
        for (String locale : BUNDLED) {
            try (InputStream in = classLoader.getResourceAsStream("locales/" + locale + ".yml")) {
                if (in != null) {
                    catalogs.put(locale, MessageCatalog.load(in, locale));
                } else if (HARD_FALLBACK_LOCALE.equals(locale)) {
                    throw new I18nException("bundled en_US catalog missing from classpath; the jar is broken");
                }
            } catch (IOException e) {
                // getResourceAsStream handles are in-memory jars/files; still, never swallow
                throw new UncheckedIOException("failed to read bundled catalog " + locale, e);
            }
        }
        return new Translator(catalogs, config.defaultLocale(), config.fallbackLocale());
    }

    /** Renders with the default locale. */
    public Component render(String key, TypedArgument... args) {
        return render(defaultLocale, key, args);
    }

    /**
     * Renders {@code key} for {@code locale} against its catalog, walking the
     * fallback chain when the key (or catalog) is missing.
     */
    public Component render(String locale, String key, TypedArgument... args) {
        for (String candidate : chain(locale)) {
            MessageCatalog catalog = catalogs.get(candidate);
            if (catalog == null) {
                continue;
            }
            var template = catalog.template(key);
            if (template.isPresent()) {
                return deserialize(template.get(), key, candidate, args);
            }
        }
        LOGGER.warn("missing i18n key '{}' in all fallback locales (requested '{}')", key, locale);
        return Component.text("missing message key: " + key, NamedTextColor.RED);
    }

    /** The locales this translator can serve, en_US first. */
    public Set<String> supportedLocales() {
        return new TreeSet<>(catalogs.keySet());
    }

    /** The resolved fallback chain for {@code locale} (requested first). Exposed for diagnostics. */
    public List<String> fallbackChain(String locale) {
        return chain(locale);
    }

    private List<String> chain(String locale) {
        var chain = new ArrayList<String>(4);
        String requested = locale == null ? null : normalizeLocale(locale);
        addIfAbsent(chain, requested);
        addIfAbsent(chain, fallbackLocale);
        addIfAbsent(chain, HARD_FALLBACK_LOCALE);
        return List.copyOf(chain);
    }

    private static void addIfAbsent(List<String> chain, String locale) {
        if (locale != null && !locale.isBlank() && !chain.contains(locale)) {
            chain.add(locale);
        }
    }

    /**
     * Normalizes loose locale input to {@code ll_CC}: {@code en-us}, {@code en_us},
     * {@code EN-US} all become {@code en_US}. Lenient on input, strict on output.
     */
    public static String normalizeLocale(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.trim().replace('-', '_').split("_");
        if (parts.length == 1) {
            return parts[0].toLowerCase(java.util.Locale.ROOT);
        }
        if (parts.length >= 2) {
            return parts[0].toLowerCase(java.util.Locale.ROOT) + "_" + parts[1].toUpperCase(java.util.Locale.ROOT);
        }
        return raw.trim();
    }

    private Component deserialize(String template, String key, String locale, TypedArgument[] args) {
        List<TagResolver> resolvers = new ArrayList<>(args.length);
        for (TypedArgument arg : args) {
            switch (arg) {
                case TypedArgument.Text text -> resolvers.add(Placeholder.unparsed(text.argument(), text.value()));
                case TypedArgument.NumberValue number ->
                        resolvers.add(Placeholder.unparsed(number.argument(), String.valueOf(number.value())));
                case TypedArgument.Flag flag ->
                        resolvers.add(Placeholder.unparsed(flag.argument(), String.valueOf(flag.value())));
                case TypedArgument.ComponentValue component ->
                        resolvers.add(Placeholder.component(component.argument(), component.value()));
            }
        }
        try {
            return miniMessage.deserialize(template, resolvers.toArray(TagResolver[]::new));
        } catch (Exception e) {
            // bad template in a catalog is a build-time bug; fail visibly but do not crash gameplay
            LOGGER.error("i18n template for key '{}' in locale '{}' failed to render", key, locale, e);
            return Component.text("invalid message: " + key, NamedTextColor.RED);
        }
    }
}
