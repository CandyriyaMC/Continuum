package lol.candyriya.continuum.api.module;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ModuleIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "continuum.inventory",
            "a.b",
            "myplugin.cool_module",
            "my-plugin.mod1",
            "a1.b2.c3",
            "x.y-z_w",
            "123.456",
            "mod.a.b.c.d.e.f"
    })
    void validIdsAreAccepted(String id) {
        assertTrue(ModuleIds.isValid(id), id);
        assertDoesNotThrow(() -> ModuleIds.requireValid(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "ab",              // single segment — no dot
            "A.b",             // uppercase
            "a.B",
            "a..b",            // empty segment
            ".a.b",            // leading dot
            "a.b.",            // trailing dot
            "a b.c",           // space
            "a.b c",
            "a.b-c d",
            "а.в",             // non-ascii
            "a.b!",            // punctuation
            "continuum",       // reserved word alone is not an id
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.b"  // 33-char segment
    })
    void invalidIdsAreRejected(String id) {
        assertFalse(ModuleIds.isValid(id), id);
        assertThrows(InvalidModuleIdException.class, () -> ModuleIds.requireValid(id));
    }

    @Test
    void nullIdIsRejected() {
        assertFalse(ModuleIds.isValid(null));
        InvalidModuleIdException thrown = assertThrows(InvalidModuleIdException.class,
                () -> ModuleIds.requireValid(null));
        assertNull(thrown.id());
    }

    @Test
    void maxLengthIsEnforced() {
        // 32+1+32+1+32+1+29 = 128 chars across four segments, each within limits
        String atLimit = String.join(".", "a".repeat(32), "a".repeat(32), "a".repeat(32), "a".repeat(29));
        String overLimit = String.join(".", "a".repeat(32), "a".repeat(32), "a".repeat(32), "a".repeat(30));

        assertEquals(128, atLimit.length());
        assertEquals(129, overLimit.length());
        assertTrue(ModuleIds.isValid(atLimit));
        assertFalse(ModuleIds.isValid(overLimit));
        assertThrows(InvalidModuleIdException.class, () -> ModuleIds.requireValid(overLimit));
    }

    @Test
    void namespaceOfReturnsTheFirstSegment() {
        assertEquals("continuum", ModuleIds.namespaceOf("continuum.inventory"));
        assertEquals("myplugin", ModuleIds.namespaceOf("myplugin.cool_module"));
        assertEquals("a", ModuleIds.namespaceOf("a.b.c"));
    }

    @Test
    void namespaceOfRejectsMalformedIds() {
        assertThrows(InvalidModuleIdException.class, () -> ModuleIds.namespaceOf("nope"));
        assertThrows(InvalidModuleIdException.class, () -> ModuleIds.namespaceOf(null));
    }

    @Test
    void isReservedOnlyMatchesContinuumNamespace() {
        assertTrue(ModuleIds.isReserved("continuum.inventory"));
        assertTrue(ModuleIds.isReserved("continuum.anything.else"));
        assertFalse(ModuleIds.isReserved("myplugin.continuum"));
        assertFalse(ModuleIds.isReserved("notcontinuum.inventory"));
    }

    @Test
    void exceptionCarriesTheOffendingId() {
        InvalidModuleIdException thrown =
                assertThrows(InvalidModuleIdException.class, () -> ModuleIds.requireValid("Bad.Id"));

        assertEquals("Bad.Id", thrown.id());
        assertTrue(thrown.getMessage().contains("Bad.Id"));
    }
}
