package lol.candyriya.continuum.paper.modules.custom;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;

/**
 * Default {@link AdapterPayloadCodec} backed by Gson. Gson is provided by the server at
 * runtime (it is a server library) and reaches the compile classpath through the
 * platform API; nothing here is bundled into the platform jar.
 *
 * <p>Gson is used deliberately instead of the shared Jackson mapper: the platform's
 * compile classpath has no Jackson (it ships on the infrastructure executors instead),
 * and adapter payloads are opaque to Continuum — the adapter owns their shape, so the
 * naming conventions of whatever engine reads and writes them must simply be the same,
 * which they are here. The module's own records go through the shared mapper as usual;
 * they are wire contract, this is not.</p>
 *
 * <p>Thread-safe and stateless; {@link Gson} handles concurrent use fine.</p>
 *
 * @since 1.0.0
 */
public final class GsonAdapterPayloadCodec implements AdapterPayloadCodec {

    private final Gson gson = new Gson();

    @Override
    public @NotNull String encode(@NotNull Object payload, @NotNull Type payloadType) {
        try {
            return gson.toJson(payload, payloadType);
        } catch (RuntimeException e) {
            throw new JsonParseException("adapter payload is not JSON-encodable: " + e.getMessage(), e);
        }
    }

    @Override
    public Object decode(@NotNull String payload, @NotNull Type payloadType) {
        try {
            return gson.fromJson(payload, payloadType);
        } catch (RuntimeException e) {
            throw new JsonParseException("adapter payload could not be decoded: " + e.getMessage(), e);
        }
    }
}
