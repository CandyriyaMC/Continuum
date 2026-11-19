package lol.candyriya.continuum.paper.modules.custom;

/**
 * The JSON engine adapters are serialized with. Seamed out so the module never names a
 * JSON library type: the default implementation (see {@link GsonAdapterPayloadCodec})
 * works on JDK types and strings only.
 *
 * <p>The codec is asked to roundtrip the adapter's own data type (resolved from the
 * adapter class hierarchy at registration time) against any UTF-8 JSON — including JSON
 * produced by a <em>different</em> implementation, because stored snapshots outlive the
 * code that wrote them.</p>
 *
 * <p>Implementations must be thread-safe and stateless; capture and restore may run the
 * codec on arbitrary threads, never on the entity thread in a blocking sense — Continuum
 * takes the encode/decode off the platform thread via an infrastructure executor.</p>
 *
 * @since 1.0.0
 */
public interface AdapterPayloadCodec {

    /**
     * Serializes {@code payload} to its JSON form.
     *
     * @param payload    the captured value, never null
     * @param payloadType the adapter's resolved payload type, never null
     * @return the JSON text, never null
     * @throws RuntimeException when the value cannot be represented as JSON; the caller
     *                          records it as a capture failure
     */
    String encode(Object payload, java.lang.reflect.Type payloadType);

    /**
     * Parses a stored (or migrated) payload back into the adapter's data type.
     *
     * @param payload     the JSON text to parse, never null
     * @param payloadType the adapter's resolved payload type, never null
     * @return the decoded value; may be null only for a JSON {@code null} literal
     * @throws RuntimeException when the text does not match the expected shape; the caller
     *                          records it as a validation failure
     */
    Object decode(String payload, java.lang.reflect.Type payloadType);
}
