package lol.candyriya.continuum.common.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateSettingsLoaderTest {

    @TempDir
    Path dir;

    private final org.slf4j.Logger logger = LoggerFactory.getLogger("test");

    @Test
    void missingFileSeedsDefaults() {
        UpdateSettings settings = UpdateSettingsLoader.load(dir, null, logger);
        assertTrue(settings.usable());
        assertTrue(Files.exists(dir.resolve("update.yml")));
        assertEquals(UpdateChannel.RELEASE, settings.channel());
        assertEquals(UpdateConsent.NOTIFY, settings.consent());
        assertTrue(settings.checkEnabled());
        assertEquals("", settings.licenseToken());
    }

    @Test
    void validCustomFileIsHonored() throws IOException {
        Files.writeString(dir.resolve("update.yml"), """
                updates:
                  channel: dev
                  check: false
                  consent: auto-install
                  license-token: "ctm_live_0123456789abcdef0123456789abcdef"
                  redirect-allow-suffixes:
                    - .cdn.example.com
                """);
        UpdateSettings settings = UpdateSettingsLoader.load(dir, null, logger);
        assertTrue(settings.usable());
        assertEquals(UpdateChannel.DEV, settings.channel());
        assertFalse(settings.checkEnabled());
        assertEquals(UpdateConsent.AUTO_INSTALL, settings.consent());
        assertEquals("ctm_live_0123456789abcdef0123456789abcdef", settings.licenseToken());
        assertEquals(List.of(".cdn.example.com"), settings.redirectAllowSuffixes());
    }

    @Test
    void brokenYamlDisablesWithReasons() throws IOException {
        Files.writeString(dir.resolve("update.yml"), "updates:\n  channel: [unclosed\n");
        UpdateSettings settings = UpdateSettingsLoader.load(dir, null, logger);
        assertFalse(settings.usable());
        assertFalse(settings.problems().isEmpty());
    }

    @Test
    void badEnumsDisableWithAllReasonsListed() throws IOException {
        Files.writeString(dir.resolve("update.yml"), """
                updates:
                  channel: canary
                  consent: yolo
                  check: not-a-boolean
                """);
        UpdateSettings settings = UpdateSettingsLoader.load(dir, null, logger);
        assertFalse(settings.usable());
        // every problem is listed, not just the first one
        assertTrue(settings.problems().size() >= 3, settings.problems().toString());
    }

    @Test
    void configYmlTokenIsAFallbackOnly() throws IOException {
        Files.writeString(dir.resolve("config.yml"), "license:\n  token: ctm_live_fallback0123456789abcdef0123456789\n");
        UpdateSettings withProbe = UpdateSettingsLoader.load(dir, dir.resolve("config.yml"), logger);
        assertEquals("ctm_live_fallback0123456789abcdef0123456789", withProbe.licenseToken());

        // update.yml wins when a token is present there
        Files.writeString(dir.resolve("update.yml"), "updates:\n  license-token: ctm_live_sheepwins0123456789abcdef0123456789\n");
        UpdateSettings direct = UpdateSettingsLoader.load(dir, dir.resolve("config.yml"), logger);
        assertEquals("ctm_live_sheepwins0123456789abcdef0123456789", direct.licenseToken());
    }

    @Test
    void unreadableConfigYmlProbeStaysSilent() throws IOException {
        Files.writeString(dir.resolve("config.yml"), "::: definitely not yaml ::: [");
        UpdateSettings settings = UpdateSettingsLoader.load(dir, dir.resolve("config.yml"), logger);
        assertTrue(settings.usable());
        assertEquals("", settings.licenseToken());
    }
}
