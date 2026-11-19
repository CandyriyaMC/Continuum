package lol.candyriya.continuum.common.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;

import java.time.Instant;

/**
 * Wire representation for {@link Instant}: epoch milliseconds as a JSON
 * number. Chosen over ISO-8601 strings because it survives int64 arithmetic
 * intact and avoids depend-on-jsr310-module forever.
 *
 * <p>The historic ISO strings are not parsed on read; schema version gates
 * handle any future format change.</p>
 */
final class InstantCodecs {

    private InstantCodecs() {
    }

    static ValueSerializer<Instant> serializer() {
        return new ValueSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
                gen.writeNumber(value.toEpochMilli());
            }
        };
    }

    static ValueDeserializer<Instant> deserializer() {
        return new ValueDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext ctxt) throws JacksonException {
                // getString() works for numeric tokens and spares us a streaming-API version check.
                String raw = parser.getString();
                try {
                    return Instant.ofEpochMilli(Long.parseLong(raw.trim()));
                } catch (NumberFormatException e) {
                    throw new JacksonException("invalid epoch-milli instant: '" + raw + "'") {
                    };
                }
            }
        };
    }
}
