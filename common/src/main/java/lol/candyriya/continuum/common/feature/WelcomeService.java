package lol.candyriya.continuum.common.feature;

import lol.candyriya.continuum.common.config.PluginConfig;
import lol.candyriya.continuum.common.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * Example feature: the join welcome message. All logic is pure and platform-independent,
 * so it is covered by plain unit tests with no server mocks.
 *
 * <p>How to add your own feature: another small class on plain data, the platform
 * just calls it from a listener and sends the result. No Bukkit/Velocity API inside.</p>
 */
public final class WelcomeService {

    private final MessageService messages;

    public WelcomeService(@NotNull MessageService messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Builds the welcome message for a player.
     *
     * @return empty when welcomes are disabled in the config, otherwise the ready component
     */
    public @NotNull Optional<Component> welcomeMessage(@NotNull PluginConfig config, @NotNull String playerName) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(playerName, "playerName");
        if (!config.welcome().enabled()) {
            return Optional.empty();
        }
        // Nickname goes through Placeholder.unparsed: tags inside the name are escaped,
        // a player named "<red>hacker" cannot break anyone's formatting.
        var body = messages.parse(
                config.welcome().message(),
                Placeholder.unparsed("player", playerName));
        return Optional.of(messages.prefixed(config.prefix(), body));
    }
}
