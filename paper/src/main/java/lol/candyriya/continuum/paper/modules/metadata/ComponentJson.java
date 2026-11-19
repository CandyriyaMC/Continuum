package lol.candyriya.continuum.paper.modules.metadata;

import net.kyori.adventure.text.Component;

/**
 * The codec boundary of this module: components in, JSON strings out, JSON
 * strings back into components. The production implementation is
 * {@link GsonComponentJson}; tests inject an in-memory double because the
 * platform-provided gson serializer is not on this module's test
 * classpath. Nothing outside this package calls either directly.
 */
interface ComponentJson {

    /** Serialize a component to its JSON string. */
    String toJson(Component component);

    /**
     * Parse a component JSON string.
     *
     * @param json    the stored string, never null
     * @param wireKey the payload key being decoded, for error context
     * @throws IllegalStateException when the input is not a valid component JSON
     */
    Component fromJson(String json, String wireKey);
}
