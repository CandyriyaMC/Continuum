package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format tests, driven through the exact mapper the production snapshot
 * pipeline builds: snake_case keys, enums as names, unknown properties tolerated.
 * If one of these fails, either the wire contract changed on purpose (bump
 * FORMAT) or an accident is about to be persisted.
 */
class PdcPayloadWireTest {

    private final PdcJacksonBridge codec = PdcJacksonBridge.create();

    private static PdcPayload sample() {
        return PdcPayload.of(List.of(
                new PdcEntry("cosmetics:trail_enabled", PdcValueType.BYTE, 1),
                new PdcEntry("rpgclasses:primary_class", PdcValueType.STRING, "warrior"),
                new PdcEntry("quests:flags", PdcValueType.TAG_CONTAINER, PdcCodec.encodeBase64(new byte[]{1, 2, 3, 4})),
                new PdcEntry("admin:vanish_weight", PdcValueType.DOUBLE, 0.75d)));
    }

    @Test
    void roundTripPreservesEverything() {
        PdcPayload original = sample();
        PdcPayload decoded = codec.roundTrip(original, PdcPayload.class);
        assertEquals(original, decoded);
    }

    @Test
    void roundTripPreservesArrayValuesByteForByte() {
        var payload = PdcPayload.of(List.of(
                new PdcEntry("a:ints", PdcValueType.INTEGER_ARRAY, PdcCodec.encodeIntArray(new int[]{0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})),
                new PdcEntry("a:longs", PdcValueType.LONG_ARRAY, PdcCodec.encodeLongArray(new long[]{0L, -1L, Long.MAX_VALUE})),
                new PdcEntry("a:bytes", PdcValueType.BYTE_ARRAY, PdcCodec.encodeBase64(new byte[]{0, 1, 127, -128}))));
        PdcPayload decoded = codec.roundTrip(payload, PdcPayload.class);

        assertArrayEquals(PdcCodec.decodeIntArray((String) payload.entries().get(0).value()),
                PdcCodec.decodeIntArray((String) decoded.entries().get(0).value()));
        assertArrayEquals(PdcCodec.decodeLongArray((String) payload.entries().get(1).value()),
                PdcCodec.decodeLongArray((String) decoded.entries().get(1).value()));
        assertEquals(payload, decoded);
    }

    @Test
    void wireShapeIsSnakeCaseWithFormatMarker() {
        String json = codec.toJson(sample());
        assertTrue(json.contains("\"format\":1"), json);
        assertTrue(json.contains("\"entries\":"), json);
        assertTrue(json.contains("\"key\":\"cosmetics:trail_enabled\""), json);
        assertTrue(json.contains("\"type\":\"BYTE\""), json);
        assertTrue(json.contains("\"type\":\"TAG_CONTAINER\""), json);
        assertTrue(json.contains("\"value\":1"), json);
        assertTrue(json.contains("\"value\":\"warrior\""), json);
    }

    @Test
    void longsAreJsonNumbersNotStrings() {
        String json = codec.toJson(PdcPayload.of(List.of(
                new PdcEntry("a:big", PdcValueType.LONG, Long.MAX_VALUE),
                new PdcEntry("a:small", PdcValueType.INTEGER, Integer.MIN_VALUE))));
        assertTrue(json.contains("\"value\":9223372036854775807"), json);
        assertTrue(json.contains("\"value\":-2147483648"), json);
    }

    @Test
    void doublesSurviveShortestRoundTrip() {
        for (double tricky : new double[]{0.1d, 1.0d / 3.0d, 3.141592653589793d, 1.0e-100d, 12345.6789d}) {
            var payload = PdcPayload.of(List.of(new PdcEntry("a:x", PdcValueType.DOUBLE, tricky)));
            assertEquals(tricky, codec.roundTrip(payload, PdcPayload.class).entries().get(0).asReal());
        }
    }

    @Test
    void floatsRoundTripThroughDoubleWithoutDrift() {
        for (float tricky : new float[]{0.1f, 1.0f / 3.0f, 3.4028235e38f, 1.4e-45f}) {
            var payload = PdcPayload.of(List.of(new PdcEntry("a:x", PdcValueType.FLOAT, tricky)));
            float back = (float) codec.roundTrip(payload, PdcPayload.class).entries().get(0).asReal();
            assertEquals(Float.floatToIntBits(tricky), Float.floatToIntBits(back));
        }
    }

    @Test
    void unknownFormatOnTheWireIsKeptButRejectedByValidation() {
        String json = "{\"format\":2,\"entries\":[{\"key\":\"quests:flags\",\"type\":\"LONG\",\"value\":1}]}";
        PdcPayload decoded = codec.fromJson(json, PdcPayload.class); // construction tolerates it
        assertEquals(2, decoded.format());
        assertFalse(PdcValidation.validate(decoded).valid()); // the gate rejects it
    }

    @Test
    void unknownEnumNameOnTheWireFailsDeserialization() {
        String json = "{\"format\":1,\"entries\":[{\"key\":\"quests:flags\",\"type\":\"FROBNICATE\",\"value\":1}]}";
        assertThrows(IllegalStateException.class, () -> codec.fromJson(json, PdcPayload.class));
    }

    @Test
    void nonFiniteNumberOnTheWireFailsDeserialization() {
        // a hand-written snapshot smuggling NaN as a string must not produce a live entry
        String json = "{\"format\":1,\"entries\":[{\"key\":\"a:b\",\"type\":\"DOUBLE\",\"value\":\"NaN\"}]}";
        assertThrows(IllegalStateException.class, () -> codec.fromJson(json, PdcPayload.class));
    }

    @Test
    void unknownPropertiesOnTheWireAreTolerated() {
        // a newer writer may add fields; this build must still read the payload
        String json = "{\"format\":1,\"entries\":[],\"future_field\":123,\"another\":{\"nested\":true}}";
        PdcPayload decoded = codec.fromJson(json, PdcPayload.class);
        assertEquals(1, decoded.format());
        assertTrue(decoded.entries().isEmpty());
    }

    @Test
    void corruptBase64OnTheWireFailsDeserialization() {
        String json = "{\"format\":1,\"entries\":[{\"key\":\"blobs:data\",\"type\":\"BYTE_ARRAY\",\"value\":\"!!! not base64 !!!\"}]}";
        assertThrows(IllegalStateException.class, () -> codec.fromJson(json, PdcPayload.class));
    }

    @Test
    void outOfRangeIntegralOnTheWireFailsDeserialization() {
        // 200 does not fit a PDC BYTE; the canonical constructor refuses it
        String json = "{\"format\":1,\"entries\":[{\"key\":\"a:b\",\"type\":\"BYTE\",\"value\":200}]}";
        assertThrows(IllegalStateException.class, () -> codec.fromJson(json, PdcPayload.class));
    }

    @Test
    void goldenPayloadMatchesTheDocumentedFormat() {
        var payload = PdcPayload.of(List.of(
                new PdcEntry("cosmetics:trail_enabled", PdcValueType.BYTE, 1),
                new PdcEntry("rpgclasses:primary_class", PdcValueType.STRING, "warrior")));
        String json = codec.toJson(payload);
        assertEquals(
                "{\"format\":1,\"entries\":["
                        + "{\"key\":\"cosmetics:trail_enabled\",\"type\":\"BYTE\",\"value\":1},"
                        + "{\"key\":\"rpgclasses:primary_class\",\"type\":\"STRING\",\"value\":\"warrior\"}"
                        + "]}",
                json);
    }
}
