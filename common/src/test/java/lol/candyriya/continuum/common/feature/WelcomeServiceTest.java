package lol.candyriya.continuum.common.feature;

import lol.candyriya.continuum.common.config.PluginConfig;
import lol.candyriya.continuum.common.message.MessageService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for WelcomeService.welcomeMessage. Behavior seam (prefix + name + disabled),
 * nothing implementation-specific.
 */
class WelcomeServiceTest {

    private final WelcomeService welcomes = new WelcomeService(new MessageService());

    private static PluginConfig config(boolean enabled) {
        return new PluginConfig("[T] ", false, new PluginConfig.Welcome(enabled, "hi <player>"));
    }

    @Test
    void welcomeContainsPrefixAndPlayerName() {
        var message = welcomes.welcomeMessage(config(true), "Steve");
        assertTrue(message.isPresent());
        assertEquals("[T] hi Steve", new MessageService().strip(message.orElseThrow()));
    }

    @Test
    void disabledWelcomeReturnsEmpty() {
        assertTrue(welcomes.welcomeMessage(config(false), "Steve").isEmpty());
    }

    @Test
    void hostileNicknameStaysLiteral() {
        var message = welcomes.welcomeMessage(config(true), "<red>admin");
        assertTrue(message.isPresent());
        assertEquals("[T] hi <red>admin", new MessageService().strip(message.orElseThrow()));
    }
}
