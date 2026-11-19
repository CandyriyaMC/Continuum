package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.I18nConfig;
import lol.candyriya.continuum.common.i18n.I18nException;
import lol.candyriya.continuum.common.i18n.TypedArgument;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityCatalogsTest {

    private static final I18nConfig I18N = new I18nConfig("en_US", "en_US", true);

    private static String plain(Component component) {
        return VelocityText.plain(component);
    }

    @Test
    void bundledCatalogsServeBothSharedAndProxyKeys() {
        var translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, null);

        String sharedKey = plain(translator.render("en_US", "admin.reload.success"));
        assertTrue(sharedKey.toLowerCase().contains("reloaded"), sharedKey);

        String proxyKey = plain(translator.render("en_US", "velocity.version",
                TypedArgument.text("version", "1.2.3"), TypedArgument.text("velocity", "4.3.1")));
        assertTrue(proxyKey.contains("1.2.3") && proxyKey.contains("4.3.1"), proxyKey);
    }

    @Test
    void overlayKeysFallBackToEnglishInOtherLocales() {
        var translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, null);

        // ru_RU exists in the shared catalog but has no proxy overlay yet; the
        // expected behavior is a clean en_US fallback, not a missing-key error
        String fallback = plain(translator.render("ru_RU", "velocity.version",
                TypedArgument.text("version", "9.8.7"), TypedArgument.text("velocity", "4.3.1")));
        assertTrue(fallback.contains("9.8.7"), fallback);
    }

    @Test
    void completelyUnknownLocaleStillRenders() {
        var translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, null);

        String rendered = plain(translator.render("fr_FR", "velocity.update.unavailable",
                TypedArgument.text("version", "5.5.5")));
        assertTrue(rendered.contains("5.5.5"), rendered);
    }

    @Test
    void externalOverrideWinsPerKeyAndKeepsTheRest(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("en_US.yml"), "velocity:\n  version: \"OVERRIDDEN <version>\"\n");

        var translator = VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, dir);

        String overridden = plain(translator.render("en_US", "velocity.version",
                TypedArgument.text("version", "2.0.0"), TypedArgument.text("velocity", "4.3.1")));
        assertTrue(overridden.contains("OVERRIDDEN 2.0.0"), overridden);

        // a key the override does not touch must survive the merge
        String untouched = plain(translator.render("en_US", "velocity.update.unavailable",
                TypedArgument.text("version", "2.0.0")));
        assertTrue(untouched.contains("2.0.0"), untouched);
    }

    @Test
    void brokenExternalCatalogFailsLoudly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("en_US.yml"), "::: just not a catalog :::");

        assertThrows(I18nException.class,
                () -> VelocityCatalogs.build(Thread.currentThread().getContextClassLoader(), I18N, dir));
    }
}
