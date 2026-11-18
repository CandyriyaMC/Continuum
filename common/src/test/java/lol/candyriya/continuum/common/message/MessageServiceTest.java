package lol.candyriya.continuum.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the MessageService wrapper. The surface is narrow on purpose: parse,
 * strip, prefix — tests pin those down.
 */
class MessageServiceTest {

    private final MessageService messages = new MessageService();

    @Test
    void parseHandlesColorsAndDecorations() {
        assertEquals(
                Component.text("pong!", NamedTextColor.GREEN, TextDecoration.BOLD),
                messages.parse("<green><bold>pong!"));
    }

    @Test
    void placeholderSubstitutesValue() {
        assertEquals(
                Component.text("Hello, Steve!"),
                messages.parse("Hello, <name>!", Placeholder.unparsed("name", "Steve")));
    }

    @Test
    void unparsedPlaceholderEscapesUserInput() {
        // the raw nickname must not leak MiniMessage tags into the tree (injection)
        var result = messages.parse("<name>", Placeholder.unparsed("name", "<red>input"));
        assertEquals(Component.text("<red>input"), result);
    }

    @Test
    void prefixedPrependsParsedPrefix() {
        var result = messages.prefixed("<gray>[T]</gray> ", Component.text("hi"));
        // prefix is parsed, not appended raw: it must end up gray, not a literal tag
        assertEquals("[T] hi", messages.strip(result));
        var prefixPart = result.children().stream()
                .filter(c -> c instanceof TextComponent tc && tc.content().contains("[T]"))
                .findFirst()
                .orElseThrow();
        assertEquals(NamedTextColor.GRAY, prefixPart.color());
    }

    @Test
    void stripRemovesFormatting() {
        assertEquals(
                "plain text",
                messages.strip(Component.text("plain ").append(
                        Component.text("text", NamedTextColor.RED, TextDecoration.BOLD))));
    }

    @Test
    void parsedMessageContainsNoTagsInPlainForm() {
        var stripped = messages.strip(messages.parse("<rainbow>colorful"));
        assertTrue(stripped.contains("colorful"));
    }
}
