package lol.candyriya.continuum.paper.modules.permissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionContextTest {

    @Test
    @DisplayName("tags are stored sorted by encoded entry regardless of input order")
    void tagsAreSorted() {
        var context = PermissionContext.of(Map.of(
                "world", "lobby",
                "server", "factions",
                "dimension", "nether"));
        assertEquals(List.of("dimension=nether", "server=factions", "world=lobby"), List.copyOf(context.tags()));
    }

    @Test
    @DisplayName("equivalent input maps produce equal contexts")
    void equalityIsOrderIndependent() {
        var first = PermissionContext.of(Map.of("world", "lobby", "server", "factions"));
        var second = PermissionContext.of(Map.of("server", "factions", "world", "lobby"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(PermissionContext.of(Map.of("world", "lobby")), first);
    }

    @Test
    @DisplayName("encode produces the stable golden form")
    void encodeGolden() {
        assertEquals("", PermissionContext.of(Map.of()).encode());
        assertEquals("world=lobby", PermissionContext.of(Map.of("world", "lobby")).encode());
        assertEquals("server=factions,world=lobby",
                PermissionContext.of(Map.of("world", "lobby", "server", "factions")).encode());
    }

    @Test
    @DisplayName("keys must match [a-z][a-z0-9_-]{0,31}")
    void keysAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("World", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("1world", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("world name", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("wor=ld", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("world,name", "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("a".repeat(33), "lobby")));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.of(Map.of("a", "  ")));
        // exactly 32 lowercase chars is fine
        var ok = PermissionContext.of(Map.of("a".repeat(32), "lobby"));
        assertEquals("a".repeat(32) + "=lobby", ok.encode());
    }

    @Test
    @DisplayName("null keys and values fail fast with clear exceptions")
    void nullElementsRejected() {
        var nullValue = new java.util.HashMap<String, String>();
        nullValue.put("world", null);
        assertThrows(NullPointerException.class, () -> PermissionContext.of(nullValue));
        assertThrows(NullPointerException.class, () -> PermissionContext.of(null));
    }

    @Test
    @DisplayName("unreserved value characters pass through unencoded")
    void unreservedValuesStayRaw() {
        var context = PermissionContext.of(Map.of("world", "Survival-1.2_x~y"));
        assertEquals("world=Survival-1.2_x~y", context.encode());
    }

    @Test
    @DisplayName("reserved and non-ascii value characters are percent-encoded, uppercase hex")
    void reservedValuesAreEncoded() {
        assertEquals("world=a%2Cb", PermissionContext.of(Map.of("world", "a,b")).encode());
        assertEquals("world=a%3Db", PermissionContext.of(Map.of("world", "a=b")).encode());
        assertEquals("world=100%25", PermissionContext.of(Map.of("world", "100%")).encode());
        assertEquals("world=a%20b", PermissionContext.of(Map.of("world", "a b")).encode());
        // "мир" in UTF-8: %D0%BC%D0%B8%D1%80
        assertEquals("world=%D0%BC%D0%B8%D1%80", PermissionContext.of(Map.of("world", "мир")).encode());
    }

    @Test
    @DisplayName("encode -> decode roundtrip is lossless for nasty values")
    void roundtripIsLossless() {
        var values = List.of(
                "plain",
                "with space",
                "with,comma",
                "with=equals",
                "percent%25",
                "trail%20",
                "мир/мир)*",
                "UPPER mixeD",
                "tab\tand\nnewline");
        for (String value : values) {
            var context = PermissionContext.of(Map.of("world", value, "server", "survival-1"));
            var decoded = PermissionContext.decode(context.encode());
            assertEquals(context, decoded, "roundtrip failed for value: " + value);
            assertEquals(context.encode(), decoded.encode());
        }
    }

    @Test
    @DisplayName("decode of the empty string yields an empty context")
    void decodeEmpty() {
        var decoded = PermissionContext.decode("");
        assertTrue(decoded.tags().isEmpty());
        assertEquals("", decoded.encode());
    }

    @Test
    @DisplayName("decode rejects malformed input")
    void decodeRejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("=lobby"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("WORLD=lobby"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world="));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a b"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a%"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a%2"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a%2b")); // lowercase hex
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a%zz"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a,"));   // trailing comma
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode(",world=a"));   // leading comma
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a,,server=b"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=="));
        assertThrows(NullPointerException.class, () -> PermissionContext.decode(null));
    }

    @Test
    @DisplayName("decode rejects duplicate keys — hand-written diagnostics with doubles are ambiguous")
    void decodeRejectsDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a,world=b"));
        assertThrows(IllegalArgumentException.class, () -> PermissionContext.decode("world=a,world=a"));
    }

    @Test
    @DisplayName("encoded entries survive a full roundtrip byte-identically")
    void encodeIsCanonical() {
        var context = PermissionContext.of(Map.of("world", "arena 1", "server", "survival-1"));
        var encoded = context.encode();
        assertEquals("server=survival-1,world=arena%201", encoded);
        assertEquals(encoded, PermissionContext.decode(encoded).encode());
    }

    @Test
    @DisplayName("tags() is unmodifiable")
    void tagsAreUnmodifiable() {
        var context = PermissionContext.of(Map.of("world", "lobby"));
        assertThrows(UnsupportedOperationException.class, () -> context.tags().add("server=x"));
        assertThrows(UnsupportedOperationException.class, () -> context.tags().remove("world=lobby"));
    }

    @Test
    @DisplayName("toString is the bare encoded form")
    void toStringIsEncode() {
        var context = PermissionContext.of(Map.of("world", "lobby"));
        assertEquals(context.encode(), context.toString());
    }

    @Test
    @DisplayName("an empty context is valid, equal to another empty context and encodes to empty")
    void emptyContext() {
        var context = PermissionContext.of(Map.of());
        assertEquals(PermissionContext.of(Map.of()), context);
        assertEquals(PermissionContext.of(Map.of()).hashCode(), context.hashCode());
        assertEquals("", context.encode());
        assertTrue(context.tags().isEmpty());
    }
}
