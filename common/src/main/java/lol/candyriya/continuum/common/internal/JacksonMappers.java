package lol.candyriya.continuum.common.internal;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;

/**
 * Shared Jackson 3 mapper configuration for everything Continuum persists or
 * sends over the wire.
 *
 * <p>Field naming is SNAKE_CASE and enum constants serialize as their declared
 * name. These are stable storage/wire contracts: renaming a record component
 * or an enum constant breaks compatibility and requires a schema version bump,
 * never a silent rename.</p>
 *
 * <p>{@code byte[]} fields serialize as Base64 strings (Jackson's built-in
 * behavior), which is how snapshots carry raw PDC bytes. Unknown JSON
 * properties are tolerated so newer writers can coexist with older readers
 * during rolling upgrades.</p>
 */
public final class JacksonMappers {

    private JacksonMappers() {
    }

    /** A fresh mapper with the Continuum wire conventions. Not thread-safe to mutate; treat as read-only. */
    public static JsonMapper create() {
        var instants = new SimpleModule("continuum-instant");
        instants.addSerializer(Instant.class, InstantCodecs.serializer());
        instants.addDeserializer(Instant.class, InstantCodecs.deserializer());
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(instants)
                .build();
    }
}
