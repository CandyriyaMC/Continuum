package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure encoding checks: base64, the big-endian int/long array layout
 * (part of the wire format), size accounting and key legality.
 */
class PdcCodecTest {

    @Test
    void base64RoundTripBinaryNoise() {
        byte[] bytes = new byte[512];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        assertArrayEquals(bytes, PdcCodec.decodeBase64(PdcCodec.encodeBase64(bytes)));
    }

    @Test
    void base64RejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.decodeBase64("!!! not base64 !!!"));
        // a single char is not a complete 4-char quantum
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.decodeBase64("A"));
        // 5 chars is one full quantum plus one stray char, which no decoder accepts
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.decodeBase64("AAAAA"));
    }

    @Test
    void intArrayRoundTripAcrossTheWholeRange() {
        int[] values = {0, 1, -1, 2, -2, Integer.MAX_VALUE, Integer.MIN_VALUE, 123456789, -987654321};
        assertArrayEquals(values, PdcCodec.decodeIntArray(PdcCodec.encodeIntArray(values)));
    }

    @Test
    void emptyIntArrayRoundTrip() {
        assertArrayEquals(new int[0], PdcCodec.decodeIntArray(PdcCodec.encodeIntArray(new int[0])));
    }

    @Test
    void longArrayRoundTripAcrossTheWholeRange() {
        long[] values = {0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 42L, -42L, 9_007_199_254_740_993L};
        assertArrayEquals(values, PdcCodec.decodeLongArray(PdcCodec.encodeLongArray(values)));
    }

    @Test
    void intArrayRejectsTruncatedPayload() {
        // 6 bytes is not a multiple of 4
        String encoded = PdcCodec.encodeBase64(new byte[]{1, 2, 3, 4, 5, 6});
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.decodeIntArray(encoded));
    }

    @Test
    void longArrayRejectsTruncatedPayload() {
        // 12 bytes is not a multiple of 8
        String encoded = PdcCodec.encodeBase64(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.decodeLongArray(encoded));
    }

    @Test
    void arrayLayoutIsBigEndianAndStable() {
        // pins the exact wire bytes: 1 int 0x01020304 -> AQIDBA==
        assertEquals("AQIDBA==", PdcCodec.encodeBase64(new byte[]{1, 2, 3, 4}));
        assertEquals("AQIDBAUGBwg=", PdcCodec.encodeLongArray(new long[]{0x0102030405060708L}));
        assertEquals("AQIDBA==", PdcCodec.encodeIntArray(new int[]{0x01020304}));
    }

    @Test
    void liveSizesAreStorageWidths() {
        assertEquals(1, PdcCodec.liveSize(PdcValueType.BYTE, (byte) 7));
        assertEquals(2, PdcCodec.liveSize(PdcValueType.SHORT, (short) 7));
        assertEquals(4, PdcCodec.liveSize(PdcValueType.INTEGER, 7));
        assertEquals(8, PdcCodec.liveSize(PdcValueType.LONG, 7L));
        assertEquals(4, PdcCodec.liveSize(PdcValueType.FLOAT, 1.5f));
        assertEquals(8, PdcCodec.liveSize(PdcValueType.DOUBLE, 1.5d));
    }

    @Test
    void liveSizesForStringsAndArrays() {
        assertEquals(6, PdcCodec.liveSize(PdcValueType.STRING, "abcdef"));
        // cyrillic is 2 bytes per char: 6 chars -> 12 bytes, not 6
        assertEquals(12, PdcCodec.liveSize(PdcValueType.STRING, "привет"));
        assertEquals(3, PdcCodec.liveSize(PdcValueType.BYTE_ARRAY, new byte[3]));
        assertEquals(16, PdcCodec.liveSize(PdcValueType.INTEGER_ARRAY, new int[4]));
        assertEquals(56, PdcCodec.liveSize(PdcValueType.LONG_ARRAY, new long[7]));
        assertEquals(100, PdcCodec.liveSize(PdcValueType.TAG_CONTAINER, new byte[100]));
    }

    @Test
    void storedSizesMatchDecodedFootprint() {
        assertEquals(1, PdcCodec.storedSize(PdcValueType.BYTE, 1L));
        assertEquals(8, PdcCodec.storedSize(PdcValueType.LONG, 1L));
        assertEquals(3, PdcCodec.storedSize(PdcValueType.STRING, "abc"));
        String ints = PdcCodec.encodeIntArray(new int[]{1, 2, 3}); // 12 decoded bytes
        assertEquals(12, PdcCodec.storedSize(PdcValueType.INTEGER_ARRAY, ints));
        String bytes = PdcCodec.encodeBase64(new byte[]{9, 9, 9, 9, 9});
        assertEquals(5, PdcCodec.storedSize(PdcValueType.BYTE_ARRAY, bytes));
        assertEquals(6, PdcCodec.storedSize(PdcValueType.TAG_CONTAINER, PdcCodec.encodeBase64(new byte[]{1, 2, 3, 4, 5, 6})));
    }

    @Test
    void toIntegralChecksRanges() {
        assertEquals(42L, PdcCodec.toIntegral(PdcValueType.BYTE, 42));
        assertEquals(-1L, PdcCodec.toIntegral(PdcValueType.LONG, -1L));
        assertEquals(127L, PdcCodec.toIntegral(PdcValueType.BYTE, 127));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.BYTE, 128));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.BYTE, -129));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.SHORT, 32768));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.SHORT, -32769));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.INTEGER, 4294967296L));
        assertThrows(IllegalArgumentException.class, () -> PdcCodec.toIntegral(PdcValueType.BYTE, "7"));
    }

    @Test
    void keyLegalityMatchesNamespacedKeyRules() {
        assertTrue(PdcCodec.isWellFormedKey("minecraft:stone"));
        assertTrue(PdcCodec.isWellFormedKey("my-plugin:sub/path.thing_ok"));
        assertTrue(PdcCodec.isWellFormedKey("a:b"));
        assertFalse(PdcCodec.isWellFormedKey("UPPER:key"));
        assertFalse(PdcCodec.isWellFormedKey("no-colon"));
        assertFalse(PdcCodec.isWellFormedKey(":no-namespace"));
        assertFalse(PdcCodec.isWellFormedKey("no-path:"));
        assertFalse(PdcCodec.isWellFormedKey("two::colons"));
        assertFalse(PdcCodec.isWellFormedKey("white space:key"));
        assertFalse(PdcCodec.isWellFormedKey(""));
        assertFalse(PdcCodec.isWellFormedKey(null));
    }

    @Test
    void namespaceOfSplitsAtFirstColon() {
        assertEquals("myplugin", PdcCodec.namespaceOf("myplugin:sub/path"));
        assertEquals("a", PdcCodec.namespaceOf("a:b"));
        assertEquals(null, PdcCodec.namespaceOf("no-colon"));
        assertEquals(null, PdcCodec.namespaceOf(null));
    }
}
