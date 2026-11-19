package lol.candyriya.continuum.paper.integrations.papi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expansion's wiring: it must answer through the PlaceholderAPI surface and
 * stay a well-behaved internal expansion (persistent, requiring nothing,
 * no eCloud). Answers are player-independent by construction.
 */
class ContinuumExpansionTest {

    private static ContinuumExpansion expansion() {
        var answers = new PlaceholderAnswers("1.0.0", "backend", List.of("inventory", "vitals"), "default");
        return new ContinuumExpansion("1.0.0", answers);
    }

    @Test
    void itIsAnInternalPersistentExpansionNamedContinuum() {
        var subject = expansion();

        assertEquals("continuum", subject.getIdentifier());
        assertEquals("CandyriyaMC", subject.getAuthor());
        assertEquals("1.0.0", subject.getVersion());
        assertTrue(subject.persist(), "internal expansions must persist across PlaceholderAPI reloads");
        assertNull(subject.getRequiredPlugin(), "internal expansions require nothing from the store");
        assertEquals(List.of("version", "modules_enabled", "role", "restore_order"), subject.getPlaceholders());
    }

    @Test
    void itAnswersEveryDocumentedPlaceholder() {
        var subject = expansion();

        assertEquals("1.0.0", subject.onPlaceholderRequest(null, "version"));
        assertEquals("inventory,vitals", subject.onPlaceholderRequest(null, "modules_enabled"));
        assertEquals("backend", subject.onPlaceholderRequest(null, "role"));
        assertEquals("default", subject.onPlaceholderRequest(null, "restore_order"));
    }

    @Test
    void itReturnsNullForUnknownKeys() {
        var subject = expansion();

        assertNull(subject.onPlaceholderRequest(null, "restore_status"));
        assertNull(subject.onPlaceholderRequest(null, "nonsense"));
        assertNull(subject.onPlaceholderRequest(null, null));
    }
}
