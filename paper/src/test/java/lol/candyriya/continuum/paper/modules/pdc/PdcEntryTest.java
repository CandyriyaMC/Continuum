package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Entry normalization: numbers collapse to Long/Double here, ranges are checked
 * once and for all, and binary values must actually be decodable base64.
 */
class PdcEntryTest {

    @Test
    void integralValuesNormalizeToLong() {
        assertEquals(1L, new PdcEntry("a:b", PdcValueType.BYTE, (byte) 1).value());
        assertEquals(2L, new PdcEntry("a:b", PdcValueType.SHORT, (short) 2).value());
        assertEquals(3L, new PdcEntry("a:b", PdcValueType.INTEGER, 3).value());
        assertEquals(4L, new PdcEntry("a:b", PdcValueType.LONG, 4L).value());
    }

    @Test
    void integralRangesAreEnforcedPerType() {
        assertEquals(127L, new PdcEntry("a:b", PdcValueType.BYTE, 127).value());
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.BYTE, 128));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.BYTE, 300));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.SHORT, 40000));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.INTEGER, 3_000_000_000L));
        // long stays long
        assertEquals(Long.MAX_VALUE, new PdcEntry("a:b", PdcValueType.LONG, Long.MAX_VALUE).value());
    }

    @Test
    void realValuesNormalizeToDouble() {
        assertEquals(1.5d, new PdcEntry("a:b", PdcValueType.FLOAT, 1.5f).value());
        assertEquals(2.5d, new PdcEntry("a:b", PdcValueType.DOUBLE, 2.5d).value());
        // an integral JSON token for a real-typed field still lands on Double
        assertEquals(3.0d, new PdcEntry("a:b", PdcValueType.DOUBLE, 3).value());
        assertEquals(4.0d, new PdcEntry("a:b", PdcValueType.FLOAT, 4L).value());
    }

    @Test
    void nonFiniteFloatsAreRejected() {
        // JSON has no NaN/Infinity - an entry carrying one would be unredeployable, so it is unconstructable
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.DOUBLE, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.DOUBLE, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.FLOAT, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.FLOAT, Float.NEGATIVE_INFINITY));
        // the exotic but legal edge: -0.0 is finite and fine
        assertEquals(-0.0d, new PdcEntry("a:b", PdcValueType.DOUBLE, -0.0d).value());
    }

    @Test
    void stringPassthrough() {
        assertEquals("warrior", new PdcEntry("a:b", PdcValueType.STRING, "warrior").value());
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.STRING, 42));
    }

    @Test
    void arrayPayloadsMustBeBase64OfTheRightWidth() {
        String ints = PdcCodec.encodeIntArray(new int[]{1, -1, Integer.MIN_VALUE});
        assertEquals(ints, new PdcEntry("a:b", PdcValueType.INTEGER_ARRAY, ints).value());

        String longs = PdcCodec.encodeLongArray(new long[]{1L, -1L, Long.MAX_VALUE});
        assertEquals(longs, new PdcEntry("a:b", PdcValueType.LONG_ARRAY, longs).value());

        String bytes = PdcCodec.encodeBase64(new byte[]{0, 127, -128});
        assertEquals(bytes, new PdcEntry("a:b", PdcValueType.BYTE_ARRAY, bytes).value());

        // 6 decoded bytes: not a multiple of 4
        String badWidth = PdcCodec.encodeBase64(new byte[6]);
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.INTEGER_ARRAY, badWidth));

        // 9 decoded bytes: not a multiple of 8
        String badWidthLong = PdcCodec.encodeBase64(new byte[9]);
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.LONG_ARRAY, badWidthLong));

        // not base64 at all
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.BYTE_ARRAY, "!!!"));

        // wrong node type entirely
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.BYTE_ARRAY, 123));
    }

    @Test
    void tagContainerCarriesBase64WithoutParsingIt() {
        String blob = PdcCodec.encodeBase64(new byte[]{1, 2, 3});
        assertEquals(blob, new PdcEntry("a:b", PdcValueType.TAG_CONTAINER, blob).value());
        assertThrows(IllegalArgumentException.class, () -> new PdcEntry("a:b", PdcValueType.TAG_CONTAINER, "!!!"));
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new PdcEntry(null, PdcValueType.BYTE, 1));
        assertThrows(NullPointerException.class, () -> new PdcEntry("a:b", null, 1));
        assertThrows(NullPointerException.class, () -> new PdcEntry("a:b", PdcValueType.BYTE, null));
    }

    @Test
    void accessorsExposeTheNormalizedShape() {
        PdcEntry integral = new PdcEntry("a:b", PdcValueType.INTEGER, 7);
        assertEquals(7L, integral.asIntegral());

        PdcEntry real = new PdcEntry("a:b", PdcValueType.FLOAT, 2.5f);
        assertEquals(2.5d, real.asReal());

        PdcEntry string = new PdcEntry("a:b", PdcValueType.STRING, "x");
        assertEquals("x", string.asString());

        PdcEntry blob = new PdcEntry("a:b", PdcValueType.BYTE_ARRAY, PdcCodec.encodeBase64(new byte[]{1}));
        assertEquals(PdcCodec.encodeBase64(new byte[]{1}), blob.asString());
    }
}
