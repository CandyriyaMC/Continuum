package lol.candyriya.continuum.common.i18n;

import net.kyori.adventure.text.Component;

/**
 * A typed, named placeholder argument for MiniMessage templates.
 *
 * <p>Named (not positional) because translators may reorder arguments freely
 * across languages; the key in {@code argument} must match the tag in the
 * template, e.g. {@code <player_name>} with argument {@code "player_name"}.</p>
 *
 * <p>Strings are always inserted unparsed (player-controlled content can't
 * smuggle markup in); numbers and booleans render as literal text; components
 * insert with styling preserved.</p>
 */
public sealed interface TypedArgument permits TypedArgument.Text,
                                              TypedArgument.NumberValue,
                                              TypedArgument.Flag,
                                              TypedArgument.ComponentValue {

    /** Placeholder key this argument fills, without angle brackets. */
    String argument();

    /** Plain text, inserted unparsed. */
    record Text(String argument, String value) implements TypedArgument {
    }

    /** Any Number; rendered with its plain textual form. */
    record NumberValue(String argument, Number value) implements TypedArgument {
    }

    /** Boolean flag rendered as true/false. */
    record Flag(String argument, boolean value) implements TypedArgument {
    }

    /** Pre-built component, inserted with styling preserved. */
    record ComponentValue(String argument, Component value) implements TypedArgument {
    }

    static TypedArgument text(String argument, String value) {
        return new Text(argument, value);
    }

    static TypedArgument number(String argument, Number value) {
        return new NumberValue(argument, value);
    }

    static TypedArgument flag(String argument, boolean value) {
        return new Flag(argument, value);
    }

    static TypedArgument component(String argument, Component value) {
        return new ComponentValue(argument, value);
    }
}
