package lol.candyriya.continuum.paper.modules.location;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocationPolicyTest {

    @Test
    void defaultsAreTheOutOfTheBoxPolicy() {
        var policy = LocationPolicy.defaults();
        assertEquals(LocationMode.LAST_KNOWN_LOCATION, policy.mode());
        assertEquals(FallbackMode.SERVER_SPAWN, policy.fallbackMode());
        assertEquals(List.of(), policy.excludeWorlds());
    }

    @Test
    void nullArgumentsMapToDefaults() {
        var policy = new LocationPolicy(null, null, null);
        assertEquals(LocationMode.LAST_KNOWN_LOCATION, policy.mode());
        assertEquals(FallbackMode.SERVER_SPAWN, policy.fallbackMode());
        assertEquals(List.of(), policy.excludeWorlds());
    }

    @Test
    void excludedWorldNamesAreTrimmedLowercasedAndDeduplicated() {
        var policy = new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION,
                List.of("  Survival ", "survival", "NETHER", ""), FallbackMode.SERVER_SPAWN);
        assertEquals(List.of("survival", "nether"), policy.excludeWorlds());
    }

    @Test
    void blankAndControlCharEntriesAreDropped() {
        String bell = "\u0007";
        var policy = new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION,
                List.of("", "   ", "w" + bell + "orld", "ends\n"), FallbackMode.SERVER_SPAWN);
        // "ends\n" trims to "ends" and is kept; the entry containing a raw control char is dropped
        assertEquals(List.of("ends"), policy.excludeWorlds());
    }

    @Test
    void exclusionMatchIsCaseInsensitiveAndTrimmed() {
        var policy = new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION,
                List.of("survival"), FallbackMode.SERVER_SPAWN);
        assertTrue(policy.isExcluded("survival"));
        assertTrue(policy.isExcluded("Survival"));
        assertTrue(policy.isExcluded(" SURVIVAL "));
        assertFalse(policy.isExcluded("survival_2"));
        assertFalse(policy.isExcluded(""));
    }

    @Test
    void exclusionWithNullOrBlankNeedleNeverMatches() {
        var policy = new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION,
                List.of("survival"), FallbackMode.SERVER_SPAWN);
        assertFalse(policy.isExcluded(null));
        assertFalse(policy.isExcluded("   "));
    }

    @Test
    void validationAcceptsASanePolicy() {
        var problems = new ArrayList<String>();
        new LocationPolicy(LocationMode.SERVER_SPAWN, List.of("survival", "the_end"), FallbackMode.SERVER_SPAWN)
                .validate(problems);
        assertEquals(List.of(), problems);
    }

    @Test
    void validationFlagsWeirdWorldNamesButNotPrintableDefaults() {
        var problems = new ArrayList<String>();
        new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION, List.of("w;rd", "under_score-1.dot space"), FallbackMode.SERVER_SPAWN)
                .validate(problems);
        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("w;rd"), problems.toString());
    }

    @Test
    void validationFlagsOverlongWorldNames() {
        var problems = new ArrayList<String>();
        new LocationPolicy(LocationMode.LAST_KNOWN_LOCATION, List.of("a".repeat(65)), FallbackMode.SERVER_SPAWN)
                .validate(problems);
        assertEquals(1, problems.size());
    }

    @Test
    void excludeWorldsListIsUnmodifiable() {
        var policy = LocationPolicy.defaults();
        assertThrows(UnsupportedOperationException.class, () -> policy.excludeWorlds().add("x"));
    }
}
