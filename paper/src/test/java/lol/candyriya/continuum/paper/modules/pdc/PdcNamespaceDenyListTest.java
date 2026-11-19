package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hard namespace deny-list: credential-shaped namespaces never sync,
 * everything else does. This is the closed-config inversion documented in
 * the module - these tests pin the list exactly so it cannot silently grow.
 */
class PdcNamespaceDenyListTest {

    @Test
    void credentialNamespacesAreDenied() {
        for (String ns : new String[]{"security", "auth", "authy", "2fa", "login", "verification", "manual"}) {
            assertTrue(PdcNamespaceDenyList.isDenied(ns), ns);
        }
    }

    @Test
    void theDeniedSetIsExactlyTheseSeven() {
        assertEquals(
                java.util.Set.of("security", "auth", "authy", "2fa", "login", "verification", "manual"),
                PdcNamespaceDenyList.alwaysDenyNamespaces());
    }

    @Test
    void everythingElseSyncs() {
        for (String ns : new String[]{"continuum", "minecraft", "mythicmobs", "rpgclasses", "quests", "cosmetics", "authme", "authentication"}) {
            // authme/authentication are not on the list on purpose: an exact-match list must not grow prefix rules
            assertFalse(PdcNamespaceDenyList.isDenied(ns), ns);
        }
    }

    @Test
    void matchingIsCaseInsensitiveForSafety() {
        assertTrue(PdcNamespaceDenyList.isDenied("AUTH"));
        assertTrue(PdcNamespaceDenyList.isDenied("Security"));
    }
}
