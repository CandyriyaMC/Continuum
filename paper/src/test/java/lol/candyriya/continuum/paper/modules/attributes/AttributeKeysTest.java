package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeKeysTest {

    @Test
    void wellFormedNamespacedKeysAreAccepted() {
        assertTrue(AttributeKeys.isValid("minecraft:max_health"));
        assertTrue(AttributeKeys.isValid("minecraft:block_interaction_range"));
        assertTrue(AttributeKeys.isValid("continuum:some_modifier"));
        assertTrue(AttributeKeys.isValid("my-mod:deep/path/value"));
        assertTrue(AttributeKeys.isValid("a.b:c_d-e.f"));
    }

    @Test
    void keysWithoutAnExplicitNamespaceAreRejected() {
        assertFalse(AttributeKeys.isValid("max_health"));
        assertFalse(AttributeKeys.isValid(""));
        assertFalse(AttributeKeys.isValid(null));
    }

    @Test
    void malformedKeysAreRejected() {
        assertFalse(AttributeKeys.isValid(":max_health"));       // empty namespace
        assertFalse(AttributeKeys.isValid("minecraft:"));         // empty value
        assertFalse(AttributeKeys.isValid("minecraft:a:b"));      // two separators
        assertFalse(AttributeKeys.isValid("Minecraft:Max_Health")); // case matters, no silent normalize
        assertFalse(AttributeKeys.isValid("minecraft:has space"));
        assertFalse(AttributeKeys.isValid("min ecraft:max_health"));
        assertFalse(AttributeKeys.isValid("minecraft:wild*card"));
    }

    @Test
    void namespaceIsExtractedOrEmpty() {
        assertEquals("minecraft", AttributeKeys.namespaceOf("minecraft:max_health"));
        assertEquals("continuum", AttributeKeys.namespaceOf("continuum:x"));
        assertEquals("", AttributeKeys.namespaceOf("max_health"));
        assertEquals("", AttributeKeys.namespaceOf(""));
        assertEquals("", AttributeKeys.namespaceOf(null));
    }
}
