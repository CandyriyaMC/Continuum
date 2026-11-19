package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * Production codec: adventure's gson component serializer, the exact class the
 * platform (Paper/Folia 26.3) ships as a provided library — the same format
 * its chat and logging paths use. Only ever compiled against the platform API,
 * never bundled into the plugin jar.
 *
 * <p>Deliberately not MiniMessage: user-set names contain arbitrary styled
 * text, and MiniMessage markup does not round-trip safely (escapes, colors and
 * argument shapes get reinterpreted). Gson JSON is the documented wire form of
 * a component and survives a full round trip.</p>
 *
 * <p>The serializer is bound lazily inside a holder so that merely
 * constructing the module on a platform whose runtime somehow lacks the class
 * fails at the point of use with the real error, not at class-load with a
 * confusing initializer trace.</p>
 */
final class GsonComponentJson implements ComponentJson {

    private static final class Holder {
        static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    }

    GsonComponentJson() {
        // no state — the serializer binds itself on first use
    }

    @Override
    public String toJson(Component component) {
        return Holder.GSON.serialize(component);
    }

    @Override
    public Component fromJson(String json, String wireKey) {
        try {
            return Holder.GSON.deserialize(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException(wireKey + " is not valid gson component JSON (" + json.length() + " chars)", e);
        }
    }
}
