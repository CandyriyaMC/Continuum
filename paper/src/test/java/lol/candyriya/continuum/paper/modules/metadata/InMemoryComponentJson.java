package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory component codec double for tests — the platform-provided gson
 * component serializer is not on this module's test classpath (it arrives with
 * the platform API at runtime), so tests that exercise engine/codec logic use
 * this registry-based double.
 *
 * <p>Round-trip fidelity contract: {@code fromJson(toJson(c)) == c} exactly;
 * unknown strings throw {@link IllegalStateException}, mirroring the failure
 * shape of the production codec. The JSON payload is synthetic and carries the
 * component's content length so budget enforcement paths behave realistically —
 * it is deliberately not attemptable as component JSON itself.</p>
 */
final class InMemoryComponentJson implements ComponentJson {

    private final Map<String, Component> componentByJson = new HashMap<>();
    private int sequence;

    @Override
    public String toJson(Component component) {
        // never hand the same token to two components; length must reflect the real content so the cap logic fires
        String json = "{\"k\":" + (sequence++) + "}" + "x".repeat(contentLength(component));
        componentByJson.put(json, component);
        return json;
    }

    /** Sums the text content of the component tree — close enough to real JSON size for budget tests. */
    private static int contentLength(Component component) {
        int length = 0;
        if (component instanceof net.kyori.adventure.text.TextComponent text) {
            length += text.content().length();
        }
        for (Component child : component.children()) {
            length += contentLength(child);
        }
        return length;
    }

    @Override
    public Component fromJson(String json, String wireKey) {
        Component component = componentByJson.get(json);
        if (component == null) {
            throw new IllegalStateException(wireKey + " is not valid gson component JSON (" + json.length() + " chars)");
        }
        return component;
    }
}
