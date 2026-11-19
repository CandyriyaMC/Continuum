package lol.candyriya.continuum.velocity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * Minimal plain-text serializer for log lines.
 *
 * <p>The platform's serializer jars are not guaranteed to be on the runtime
 * classpath for plugins (the proxy ships the API surface, not every module),
 * so log-worthy components (kick reasons, click payloads) get flattened here
 * with nothing but the Component tree itself.</p>
 */
public final class VelocityText {

    private VelocityText() {
    }

    /** Joins the textual content of the component tree, depth-first. Non-text parts are skipped. */
    public static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        append(component, out);
        return out.toString();
    }

    private static void append(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        for (Component child : component.children()) {
            append(child, out);
        }
    }
}
