package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base64CodecTest {

    @Test
    @DisplayName("roundtrip: arbitrary binary survives encode→decode")
    void roundtripBinary() {
        var random = new Random(20261119L);
        for (int i = 0; i < 20; i++) {
            var bytes = new byte[random.nextInt(513)];
            random.nextBytes(bytes);
            String encoded = Base64Codec.encode(bytes);
            assertArrayEquals(bytes, Base64Codec.decode("test", encoded), "iteration " + i);
        }
    }

    @Test
    @DisplayName("roundtrip: empty byte array encodes to empty string and decodes back")
    void roundtripEmpty() {
        assertEquals("", Base64Codec.encode(new byte[0]));
        assertArrayEquals(new byte[0], Base64Codec.decode("test", ""));
    }

    @Test
    @DisplayName("unicode payload bytes roundtrip (utf-8 encoded content is just binary)")
    void roundtripUnicode() {
        byte[] bytes = "héllo wörld — привет — 世界".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(bytes, Base64Codec.decode("test", Base64Codec.encode(bytes)));
    }

    @Test
    @DisplayName("hostile base64 is rejected with section context, not a bare IllegalArgumentException")
    void rejectsGarbage() {
        var e = assertThrows(InventoryStateException.class, () -> Base64Codec.decode("armor", "!!!not base64!!!"));
        assertTrue(e.getMessage().contains("armor"), "message should name the section, got: " + e.getMessage());
    }

    @Test
    @DisplayName("null section is rejected with section context")
    void rejectsNull() {
        var e = assertThrows(InventoryStateException.class, () -> Base64Codec.decode("cursor", null));
        assertTrue(e.getMessage().contains("cursor"));
    }

    @Test
    @DisplayName("oversize section is rejected before decoding (base64 estimate exceeds the cap)")
    void rejectsOversize() {
        // 4 * 1024 * 1024 decoded cap -> base64 length just above cap * 4/3
        int length = (int) ((Base64Codec.MAX_SECTION_BYTES + 1024) / 3L) * 4;
        char[] chunk = new char[length];
        Arrays.fill(chunk, 'A');
        String huge = new String(chunk);
        var e = assertThrows(InventoryStateException.class, () -> Base64Codec.decode("main", huge));
        assertTrue(e.getMessage().contains("main"));
    }

    @Test
    @DisplayName("encode rejects null input instead of producing NBT-adjacent nonsense")
    void encodeRejectsNull() {
        assertThrows(NullPointerException.class, () -> Base64Codec.encode(null));
    }
}
