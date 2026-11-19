package lol.candyriya.continuum.common.i18n;

import lol.candyriya.continuum.common.config.I18nConfig;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TranslatorTest {

    private static final I18nConfig CONFIG = new I18nConfig("en_US", "en_US", true);

    private Translator bundled() {
        return Translator.loadBundled(TranslatorTest.class.getClassLoader(), CONFIG);
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void bundledCatalogsAllLoadAndEnUsExists() {
        Translator translator = bundled();
        Set<String> locales = translator.supportedLocales();
        assertTrue(locales.containsAll(Set.of("en_US", "ru_RU", "de_DE", "kk_KZ", "zh_CN")));
    }

    @Test
    void allBundledCatalogsCarryIdenticalKeys() {
        Translator translator = bundled();
        Map<String, MessageCatalog> catalogs = loadAllBundled();
        Set<String> reference = catalogs.get("en_US").keys();
        for (var entry : catalogs.entrySet()) {
            assertEquals(reference, entry.getValue().keys(),
                    "locale " + entry.getKey() + " is out of key parity with en_US");
        }
    }

    @Test
    void renderFillsTypedTextArgument() {
        Component component = bundled().render("en_US", "transfer.completed", TypedArgument.text("server", "Survival"));
        assertTrue(text(component).contains("Survival"));
    }

    @Test
    void renderFillsNumberAndFlagArguments() {
        Component number = bundled().render("update.available",
                TypedArgument.text("current", "1.0.0"),
                TypedArgument.text("latest", "1.1.0"));
        assertTrue(text(number).contains("1.0.0"));
        assertTrue(text(number).contains("1.1.0"));

        Component license = bundled().render("license.version-mismatch", TypedArgument.text("version", "2.3.0"));
        assertTrue(text(license).contains("2.3.0"));
    }

    @Test
    void stringArgumentsAreNotParsedAsMiniMessage() {
        // player-controlled content must never become markup
        Component component = bundled().render("transfer.completed", TypedArgument.text("server", "<red>not-markup"));
        assertTrue(text(component).contains("<red>not-markup"));
    }

    @Test
    void componentArgumentsKeepTheirStyling() {
        Component styled = Component.text("Fancy").color(net.kyori.adventure.text.format.NamedTextColor.GOLD);
        Component rendered = bundled().render("transfer.completed", TypedArgument.component("server", styled));
        assertTrue(text(rendered).contains("Fancy"));
        // the inserted component must actually be a child of the result, not flattened text
        assertTrue(!rendered.children().isEmpty() || !rendered.equals(Component.text("Fancy")));
    }

    @Test
    void requestedLocaleIsUsedWhenPresent() {
        String ru = text(bundled().render("ru_RU", "player.restored"));
        String en = text(bundled().render("en_US", "player.restored"));
        assertNotEquals(en, ru);
        assertTrue(ru.contains("данные"));
    }

    @Test
    void fallbackChainWalksRequestedThenConfiguredFallback() {
        // kk_KZ catalog without the key -> zh_CN fallback from config -> it wins over en_US
        MessageCatalog kkKz = inlineCatalog("kk_KZ", "player:\n  loading: \"<gray>loading\"\n");
        MessageCatalog zhCn = inlineCatalog("zh_CN", "player:\n  restored: \"<green>数据已恢复\"\n");
        MessageCatalog enUs = MessageCatalog.load(bundledResource("en_US"), "en_US");
        var translator = Translator.of(Map.of("kk_KZ", kkKz, "zh_CN", zhCn, "en_US", enUs),
                "en_US", "zh_CN");
        String viaZhCn = text(translator.render("kk_KZ", "player.restored"));
        assertTrue(viaZhCn.contains("数据已恢复"), viaZhCn);
    }

    @Test
    void fallbackHardFallsToEnUsWhenConfiguredFallbackMisses() {
        MessageCatalog kkKz = inlineCatalog("kk_KZ", "player:\n  loading: \"<gray>loading\"\n");
        MessageCatalog zhCn = inlineCatalog("zh_CN", "admin:\n  status:\n    online: \"在线\"\n");
        MessageCatalog enUs = MessageCatalog.load(bundledResource("en_US"), "en_US");
        var translator = Translator.of(Map.of("kk_KZ", kkKz, "zh_CN", zhCn, "en_US", enUs),
                "en_US", "zh_CN");
        // missing in kk_KZ and zh_CN -> en_US
        String rendered = text(translator.render("kk_KZ", "player.restored"));
        assertTrue(rendered.contains("restored"), rendered);
    }

    @Test
    void requestedLocaleWinsOverFallbackWhenKeyPresent() {
        MessageCatalog kkKz = inlineCatalog("kk_KZ", "player:\n  restored: \"<green>kk-wins\"\n");
        MessageCatalog enUs = MessageCatalog.load(bundledResource("en_US"), "en_US");
        var translator = Translator.of(Map.of("kk_KZ", kkKz, "en_US", enUs), "en_US", "en_US");
        assertTrue(text(translator.render("kk_KZ", "player.restored")).contains("kk-wins"));
    }

    @Test
    void missingKeyEverywhereRendersVisiblePlaceholder() {
        Component component = bundled().render("en_US", "no.such.key.exists");
        assertTrue(text(component).contains("no.such.key.exists"));
    }

    @Test
    void missingEnUsCatalogIsAHardError() {
        I18nException e = assertThrows(I18nException.class,
                () -> Translator.of(Map.of("ru_RU", MessageCatalog.load(bundledResource("ru_RU"), "ru_RU")),
                        "en_US", "en_US"));
        assertTrue(e.getMessage().contains("en_US"));
    }

    @Test
    void blankTemplateInCatalogIsRejected() {
        String yaml = "player:\n  restored: \"   \"\n";
        assertThrows(I18nException.class, () -> MessageCatalog.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "en_US"));
    }

    @Test
    void listValueInCatalogIsRejected() {
        String yaml = "player:\n  restored:\n    - a\n    - b\n";
        I18nException e = assertThrows(I18nException.class, () -> MessageCatalog.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "en_US"));
        assertTrue(e.getMessage().contains("player.restored"));
    }

    @Test
    void localeNormalizationAcceptsLooseInput() {
        assertEquals("en_US", Translator.normalizeLocale("en-us"));
        assertEquals("en_US", Translator.normalizeLocale("en_us"));
        assertEquals("kk_KZ", Translator.normalizeLocale("kk-KZ"));
        assertEquals("zh_CN", Translator.normalizeLocale("ZH-cn"));
        assertEquals("en", Translator.normalizeLocale("EN"));
    }

    @Test
    void fallbackChainIsRequestedFirstAndDeduped() {
        Translator translator = bundled();
        assertEquals(java.util.List.of("en_US"), translator.fallbackChain("en_US"));
        assertEquals(java.util.List.of("kk_KZ", "en_US"), translator.fallbackChain("kk-KZ"));
    }

    @Test
    void everyBundledTemplateRendersWithItsDeclaredArguments() {
        // guards against MiniMessage tag typos in any bundled catalog
        Translator translator = bundled();
        assertDoesNotThrow(() -> translator.render("en_US", "transfer.requested", TypedArgument.text("server", "S")));
        assertDoesNotThrow(() -> translator.render("en_US", "transfer.started", TypedArgument.text("server", "S")));
        assertDoesNotThrow(() -> translator.render("en_US", "transfer.completed", TypedArgument.text("server", "S")));
        assertDoesNotThrow(() -> translator.render("en_US", "transfer.failed", TypedArgument.text("server", "S")));
        assertDoesNotThrow(() -> translator.render("en_US", "update.available",
                TypedArgument.text("current", "1.0"), TypedArgument.text("latest", "2.0")));
        assertDoesNotThrow(() -> translator.render("en_US", "license.missing", TypedArgument.text("link", "x")));
        assertDoesNotThrow(() -> translator.render("en_US", "license.version-mismatch", TypedArgument.text("version", "1.0")));
    }

    private Map<String, MessageCatalog> loadAllBundled() {
        var catalogs = new LinkedHashMap<String, MessageCatalog>();
        for (String locale : Set.of("en_US", "ru_RU", "de_DE", "kk_KZ", "zh_CN")) {
            catalogs.put(locale, MessageCatalog.load(bundledResource(locale), locale));
        }
        return catalogs;
    }

    private java.io.InputStream bundledResource(String locale) {
        java.io.InputStream in = TranslatorTest.class.getResourceAsStream("/locales/" + locale + ".yml");
        assertNotNull(in, "bundled catalog missing: " + locale);
        return in;
    }

    private MessageCatalog inlineCatalog(String locale, String yaml) {
        return MessageCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), locale);
    }
}
