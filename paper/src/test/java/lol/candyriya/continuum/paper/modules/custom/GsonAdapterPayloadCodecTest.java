package lol.candyriya.continuum.paper.modules.custom;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonAdapterPayloadCodecTest {

    private final GsonAdapterPayloadCodec codec = new GsonAdapterPayloadCodec();

    record Nested(String name, List<String> tags, int level) {
    }

    record WithMap(Map<String, Integer> scores) {
    }

    @Test
    void nestedRecordRoundtrip() {
        var type = Nested.class;
        var original = new Nested("name", List.of("a", "b", "c"), 3);
        String json = codec.encode(original, type);
        assertNotNull(json);
        Object decoded = codec.decode(json, type);
        assertEquals(original, decoded);
    }

    @Test
    void mapRoundtrip() {
        var type = WithMap.class;
        var original = new WithMap(Map.of("alpha", 1, "beta", 2));
        String json = codec.encode(original, type);
        Object decoded = codec.decode(json, type);
        assertEquals(original, decoded);
    }

    @Test
    void genericListTypeRoundtrip() throws Exception {
        var type = new com.google.gson.reflect.TypeToken<List<Nested>>() {
        }.getType();
        var original = List.of(new Nested("a", List.of("x"), 1), new Nested("b", List.of(), 2));
        String json = codec.encode(original, type);
        @SuppressWarnings("unchecked")
        List<Nested> decoded = (List<Nested>) codec.decode(json, type);
        assertEquals(original, decoded);
    }

    @Test
    void decodeInvalidJsonThrowsOurWrapper() {
        assertThrows(JsonParseException.class,
                () -> codec.decode("{not json", Nested.class));
    }

    @Test
    void encodeProducesStableJson() {
        var original = new Nested("payload", List.of("tag"), 1);
        String json = codec.encode(original, Nested.class);
        assertTrue(json.contains("\"name\":\"payload\""));
        assertTrue(json.contains("\"level\":1"));
    }
}
