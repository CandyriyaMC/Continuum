package lol.candyriya.continuum.common.i18n;

import lol.candyriya.continuum.common.config.I18nConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial coverage for the bundled MiniMessage catalogs: every key in
 * every locale must render for a full argument set, and the placeholder
 * vocabulary must stay consistent across locales and with the argument names
 * the production code actually passes.
 *
 * <p>How placeholders are found: scan the template for
 * {@code <name>} tags that are not known MiniMessage built-in tags; everything
 * else matching the shape is treated as a placeholder and a fake argument is
 * supplied for it. The list of built-in tags below is the union of what this
 * repository's catalogs use (colors + a few formatting tags) — if a catalog
 * ever uses a built-in tag that is not listed here, the test passes anyway
 * (it just also treats it as a placeholder, which is harmless), but a
 * placeholder that collides with a built-in name would render wrongly in
 * production too, so that direction is worth catching by review.</p>
 *
 * <p>A key whose template fails to deserialize falls back to a red
 * "invalid message: ..." component (the translator logs and keeps the game
 * running); these tests treat that fallback as a failure, which is exactly
 * what the fallback is there to be caught by.</p>
 */
class MiniMessageCatalogAdversarialTest {

    private static final I18nConfig CONFIG = new I18nConfig("en_US", "en_US", true);
    private static final List<String> LOCALES = List.of("en_US", "ru_RU", "de_DE", "kk_KZ", "zh_CN");

    // MiniMessage tags that are part of the markup language itself, not placeholders
    private static final Set<String> MINIMESSAGE_BUILTINS = Set.of(
            // named colors and shadow forms used in the catalogs
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white",
            // formatting, just so a future template can use them freely
            "obfuscated", "obf", "bold", "b", "strikethrough", "st",
            "underlined", "u", "italic", "i", "reset", "newline", "br",
            "gradient", "rainbow", "font", "insert",
            "hover", "click", "keybind", "lang", "translatable");

    private static final Pattern TAG = Pattern.compile("<([a-z][a-z0-9_-]*)>");

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /**
     * Argument names the production (and test) code passes at render sites.
     * A catalog that drops or renames one of these ships raw-tag text to
     * players — the tripwire is intentional. Update this map together with
     * the call sites.
     */
    // player.restore-failed-kick: paper RestoreGate; transfer.*: render sites and catalog smoke
    // tests; update.available and license.*: the admin/license command surface. Note that
    // transfer.failed is intentionally not listed: the catalog comments document an unused
    // "server" argument for it, which no locale template actually consumes.
    private static final Map<String, Set<String>> REPO_CALL_SITE_ARGS = Map.of(
            "player.restore-failed-kick", Set.of("failures", "limit"),
            "transfer.requested", Set.of("server"),
            "transfer.started", Set.of("server"),
            "transfer.completed", Set.of("server"),
            "update.available", Set.of("current", "latest"),
            "license.missing", Set.of("link"),
            "license.version-mismatch", Set.of("version"));

    private static Translator bundled() {
        return Translator.loadBundled(MiniMessageCatalogAdversarialTest.class.getClassLoader(), CONFIG);
    }

    private static Map<String, MessageCatalog> bundledCatalogs() {
        Map<String, MessageCatalog> catalogs = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            catalogs.put(locale, MessageCatalog.load(resource(locale), locale));
        }
        return catalogs;
    }

    private static InputStream resource(String locale) {
        InputStream in = MiniMessageCatalogAdversarialTest.class.getResourceAsStream("/locales/" + locale + ".yml");
        assertNotNull(in, "bundled catalog missing: " + locale);
        return in;
    }

    private static List<String> placeholdersOf(String template) {
        List<String> placeholders = new ArrayList<>();
        Matcher matcher = TAG.matcher(template);
        while (matcher.find()) {
            String tag = matcher.group(1);
            if (!MINIMESSAGE_BUILTINS.contains(tag) && !placeholders.contains(tag)) {
                placeholders.add(tag);
            }
        }
        return placeholders;
    }

    private static String renderPlain(Translator translator, String locale, String key, List<String> placeholders) {
        TypedArgument[] args = placeholders.stream()
                .map(name -> TypedArgument.text(name, "arg-" + name))
                .toArray(TypedArgument[]::new);
        Component component = translator.render(locale, key, args);
        return PLAIN.serialize(component);
    }

    @Test
    void everyKeyInEveryLocaleRendersWithItsFullArgumentSet() {
        Translator translator = bundled();
        Map<String, MessageCatalog> catalogs = bundledCatalogs();

        int rendered = 0;
        for (Map.Entry<String, MessageCatalog> catalogEntry : catalogs.entrySet()) {
            String locale = catalogEntry.getKey();
            for (String key : new TreeSet<>(catalogEntry.getValue().keys())) {
                List<String> placeholders = placeholdersOf(catalogEntry.getValue().template(key).orElseThrow());
                String text = renderPlain(translator, locale, key, placeholders);

                assertFalse(text.contains("invalid message: " + key),
                        "locale " + locale + " key " + key + " failed to deserialize: " + text);
                assertFalse(text.contains("missing message key:"),
                        "locale " + locale + " key " + key + " fell through the fallback chain: " + text);
                // a placeholder left raw in the output means an argument did not reach the template
                for (String placeholder : placeholders) {
                    assertFalse(text.contains("<" + placeholder + ">"),
                            "locale " + locale + " key " + key + " left <" + placeholder + "> unresolved: " + text);
                    assertTrue(text.contains("arg-" + placeholder),
                            "locale " + locale + " key " + key + " did not interpolate <" + placeholder + ">: " + text);
                }
                rendered++;
            }
        }
        // guard the total so the loop cannot silently shrink (5 locales x every shared key)
        assertEquals(5 * catalogs.get("en_US").size(), rendered,
                "expected every key in every locale to be rendered");
    }

    @Test
    void placeholderVocabularyIsIdenticalAcrossLocalesForEveryKey() {
        Map<String, MessageCatalog> catalogs = bundledCatalogs();
        MessageCatalog reference = catalogs.get("en_US");

        for (String key : new TreeSet<>(reference.keys())) {
            Set<String> expected = new TreeSet<>(placeholdersOf(reference.template(key).orElseThrow()));
            for (Map.Entry<String, MessageCatalog> catalogEntry : catalogs.entrySet()) {
                String locale = catalogEntry.getKey();
                String template = catalogEntry.getValue().template(key)
                        .orElseThrow(() -> new AssertionError("key " + key + " missing in " + locale));
                Set<String> actual = new TreeSet<>(placeholdersOf(template));
                assertTrue(expected.equals(actual),
                        "locale " + locale + " key " + key + " placeholders " + actual
                                + " do not match en_US " + expected);
            }
        }
    }

    @Test
    void catalogsCoverEveryArgumentNameUsedByRenderCallSites() {
        Map<String, MessageCatalog> catalogs = bundledCatalogs();

        for (Map.Entry<String, Set<String>> callSite : REPO_CALL_SITE_ARGS.entrySet()) {
            String key = callSite.getKey();
            for (Map.Entry<String, MessageCatalog> catalogEntry : catalogs.entrySet()) {
                String locale = catalogEntry.getKey();
                String template = catalogEntry.getValue().template(key)
                        .orElseThrow(() -> new AssertionError("key " + key + " missing in " + locale));
                Set<String> placeholders = new TreeSet<>(placeholdersOf(template));
                assertTrue(placeholders.containsAll(callSite.getValue()),
                        "locale " + locale + " key " + key + " must carry placeholders " + callSite.getValue()
                                + " — the call sites pass them; found " + placeholders);
            }
        }
    }

    @Test
    void renderingDoesNotLeakMarkupFromTextArguments() {
        // an argument carrying markup must stay literal in every locale — the exact guard
        // TranslatorTest exercises for one key, repeated here for every key that takes text
        Translator translator = bundled();
        Map<String, MessageCatalog> catalogs = bundledCatalogs();
        for (Map.Entry<String, MessageCatalog> catalogEntry : catalogs.entrySet()) {
            String locale = catalogEntry.getKey();
            for (String key : new TreeSet<>(catalogEntry.getValue().keys())) {
                List<String> placeholders = placeholdersOf(catalogEntry.getValue().template(key).orElseThrow());
                TypedArgument[] args = placeholders.stream()
                        .map(name -> TypedArgument.text(name, "<red>pwned-" + name))
                        .toArray(TypedArgument[]::new);
                String text = PLAIN.serialize(translator.render(locale, key, args));
                for (String placeholder : placeholders) {
                    assertTrue(text.contains("<red>pwned-" + placeholder),
                            "locale " + locale + " key " + key + " did not insert <" + placeholder + "> unparsed: " + text);
                }
            }
        }
    }
}
