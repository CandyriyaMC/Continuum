package lol.candyriya.continuum.paper.modules.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestorePlanTest {

    private static Map<String, Integer> sections(Object... kv) {
        var map = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return map;
    }

    private static final Map<String, Integer> VANILLA = sections("main", 36, "armor", 4, "extra", 1, "crafting", 4, "cursor", 1);

    @Test
    @DisplayName("exact match is applicable with all sections EXACT")
    void exactIsApplicable() {
        var plan = RestorePlan.of(sections("main", 36, "armor", 4, "extra", 1, "crafting", 4, "cursor", 1), VANILLA, false);
        assertTrue(plan.applicable());
        assertNull(plan.describeRejected());
        assertNull(plan.describeLossy());
        for (RestorePlan.SectionPlan section : plan.sections()) {
            assertEquals(RestorePlan.SectionAction.EXACT, section.action());
        }
    }

    @Test
    @DisplayName("strict mode rejects any slot-count skew, nothing is 'fixed' silently")
    void strictRejectsSkew() {
        var plan = RestorePlan.of(sections("main", 40), sections("main", 36), false);
        assertFalse(plan.applicable());
        assertNotNull(plan.describeRejected());
        assertTrue(plan.describeRejected().contains("main"));
        assertEquals(RestorePlan.SectionAction.REJECT, plan.sections().get(0).action());

        var shortPlan = RestorePlan.of(sections("armor", 2), sections("armor", 4), false);
        assertFalse(shortPlan.applicable());
    }

    @Test
    @DisplayName("lenient mode pads a short section instead of rejecting")
    void lenientPadsShort() {
        var plan = RestorePlan.of(sections("extra", 0), sections("extra", 1), true);
        assertTrue(plan.applicable());
        assertEquals(RestorePlan.SectionAction.PAD, plan.sections().get(0).action());
        assertNull(plan.describeLossy());
    }

    @Test
    @DisplayName("lenient mode truncates a long section and says so out loud")
    void lenientTruncatesLong() {
        var plan = RestorePlan.of(sections("main", 40), sections("main", 36), true);
        assertTrue(plan.applicable());
        assertEquals(RestorePlan.SectionAction.TRUNCATE, plan.sections().get(0).action());
        assertNotNull(plan.describeLossy());
        assertTrue(plan.describeLossy().contains("main"));
    }

    @Test
    @DisplayName("rejected sections are reported together, not just the first one")
    void reportsAllRejections() {
        var plan = RestorePlan.of(sections("main", 41, "armor", 3), VANILLA, false);
        assertFalse(plan.applicable());
        assertTrue(plan.describeRejected().contains("main"));
        assertTrue(plan.describeRejected().contains("armor"));
    }

    @Test
    @DisplayName("a mixed plan keeps the exact sections EXACT even when others are rejected")
    void mixedPlanStrict() {
        var plan = RestorePlan.of(sections("main", 36, "crafting", 9), VANILLA, false);
        assertFalse(plan.applicable());
        var actions = plan.sections().stream().map(RestorePlan.SectionPlan::action).toList();
        assertTrue(actions.contains(RestorePlan.SectionAction.EXACT));
        assertTrue(actions.contains(RestorePlan.SectionAction.REJECT));
    }

    @Test
    @DisplayName("zero-length section pads up in lenient mode; zero-slot target is rejected")
    void zeroEdgeCases() {
        var padAll = RestorePlan.of(sections("extra", 0), sections("extra", 1), true);
        assertEquals(RestorePlan.SectionAction.PAD, padAll.sections().get(0).action());

        var noSlot = RestorePlan.of(sections("extra", 1), sections("extra", 0), true);
        assertEquals(RestorePlan.SectionAction.TRUNCATE, noSlot.sections().get(0).action());
    }

    @Test
    @DisplayName("adjust: PAD grows the array with nulls, TRUNCATE drops the tail, EXACT is a no-op")
    void adjustMath() {
        var padded = Sections.adjust(new String[] {"a", "b"}, 4, RestorePlan.SectionAction.PAD);
        assertEquals(4, padded.length);
        assertEquals("a", padded[0]);
        assertEquals("b", padded[1]);
        assertNull(padded[2]);
        assertNull(padded[3]);

        var truncated = Sections.adjust(new String[] {"a", "b", "c", "d", "e", "f"}, 4, RestorePlan.SectionAction.TRUNCATE);
        assertEquals(4, truncated.length);
        assertEquals("d", truncated[3]);

        var exact = Sections.adjust(new String[] {"x", "y"}, 2, RestorePlan.SectionAction.EXACT);
        assertEquals(2, exact.length);
    }

    @Test
    @DisplayName("adjust throws when handed a REJECT action — a rejected section must never reach a write")
    void adjustRejectsReject() {
        assertThrows(InventoryStateException.class,
                () -> Sections.adjust(new String[] {"a"}, 4, RestorePlan.SectionAction.REJECT));
    }

    @Test
    @DisplayName("unmapped section is rejected, never silently ignored — even in lenient mode")
    void rejectsUnmappedSection() {
        var plan = RestorePlan.of(sections("main", 36, "mystery", 1), VANILLA, true);
        assertTrue(plan.sections().stream().anyMatch(s -> s.section().equals("mystery")
                && s.action() == RestorePlan.SectionAction.REJECT));
    }

    @Test
    @DisplayName("unmapped target is rejected too — plan must know the target shape")
    void rejectsUnmappedTarget() {
        var plan = RestorePlan.of(sections("mystery", 1), Map.of(), false);
        assertFalse(plan.applicable());
    }
}
