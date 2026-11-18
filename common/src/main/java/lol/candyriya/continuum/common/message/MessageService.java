package lol.candyriya.continuum.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Thin wrapper over MiniMessage so the rest of the code does not drag Adventure
 * singletons around or parse strings in five different ways.
 *
 * <p>Security note: user input (nicknames, chat input) is only substituted via
 * {@code Placeholder.unparsed/parsed} at the call site, never by string concatenation.
 * Otherwise a name like {@code <red>} breaks or hijacks the formatting — classic
 * MiniMessage injection.</p>
 */
public final class MessageService {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    /** Parses a MiniMessage string with resolvers (placeholders). */
    public @NotNull Component parse(@NotNull String text, TagResolver @NotNull ... resolvers) {
        return miniMessage.deserialize(text, resolvers);
    }

    /** Message with the config prefix. The prefix is parsed separately so tags from the
     * config cannot swallow the message's own placeholders. */
    public @NotNull Component prefixed(@NotNull String prefix, @NotNull Component message) {
        return parse(prefix).append(message);
    }

    /** Strips all formatting, for logs and places where components are not supported. */
    public @NotNull String strip(@NotNull Component component) {
        return plain.serialize(component);
    }
}
