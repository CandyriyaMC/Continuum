package lol.candyriya.continuum.paper.modules.vitals;

import lol.candyriya.continuum.common.internal.JacksonMappers;

/**
 * Test-only bridge for payload codec roundtrips.
 *
 * <p>Why reflection: {@code :common} keeps Jackson as an implementation
 * dependency, so {@code tools.jackson.*} is on the test <em>runtime</em>
 * classpath but not on the test <em>compile</em> classpath. Everything here
 * therefore works through {@code Class.forName} + {@code Method} invocation
 * and never names a Jackson type statically. The mapper used is the exact
 * same instance the production snapshot layer builds (snake_case keys, enums
 * as names, unknown properties tolerated), so a failure here means a payload
 * that would not survive the snapshot pipeline as well.</p>
 */
public final class JacksonCodecBridge {

    private final Object mapper;
    private final Class<?> mapperClass;
    private final Class<?> jsonNodeClass;

    private JacksonCodecBridge() {
        // reflection only
        Object created;
        try {
            created = Class.forName("lol.candyriya.continuum.common.internal.JacksonMappers")
                    .getMethod("create")
                    .invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("common JacksonMappers is not on the test runtime classpath", e);
        }
        this.mapper = created;
        this.mapperClass = created.getClass();
        try {
            this.jsonNodeClass = Class.forName("tools.jackson.databind.JsonNode");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("tools.jackson.databind.JsonNode is not on the test runtime classpath", e);
        }
    }

    public static JacksonCodecBridge create() {
        return new JacksonCodecBridge();
    }

    /** Serializes the payload to its JSON wire form. */
    public String toJson(Object payload) {
        try {
            Object tree = mapperClass.getMethod("valueToTree", Object.class).invoke(mapper, payload);
            return (String) mapperClass.getMethod("writeValueAsString", Object.class).invoke(mapper, tree);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to serialize payload " + payload, e);
        }
    }

    /** Deserializes {@code json} back into a payload of {@code type}. */
    public <T> T fromJson(String json, Class<T> type) {
        try {
            Object node = mapperClass.getMethod("readTree", String.class).invoke(mapper, json);
            Object value = mapperClass.getMethod("treeToValue", jsonNodeClass, Class.class).invoke(mapper, node, type);
            return type.cast(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to deserialize payload into " + type.getSimpleName() + ": " + json, e);
        }
    }

    /** Full roundtrip: payload -&gt; JSON -&gt; payload. */
    public <T> T roundTrip(T payload, Class<T> type) {
        return fromJson(toJson(payload), type);
    }
}
