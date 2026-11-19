package lol.candyriya.continuum.paper.modules.metadata;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision matrix is the module's core artifact — these tests pin it down so
 * a future edit cannot silently widen the synced surface, leave an excluded
 * entry unexplained, or drift an auditable claim.
 */
class MetadataMatrixTest {

    @Test
    void applicableSetIsExactlyTheTwoSyncedProfileNamesInWireOrder() {
        assertEquals(List.of(MetadataEntry.DISPLAY_NAME, MetadataEntry.PLAYER_LIST_NAME),
                MetadataEntry.applicable());
    }

    @Test
    void syncedWireKeysMatchThePayloadContract() {
        assertEquals("display_name", MetadataEntry.DISPLAY_NAME.wireKey());
        assertEquals("player_list_name", MetadataEntry.PLAYER_LIST_NAME.wireKey());
    }

    @Test
    void excludedEntriesCarryNoWireKey() {
        for (MetadataEntry entry : MetadataEntry.excluded()) {
            assertNull(entry.wireKey(), entry + " must not have a wire key — excluded means never serialized");
        }
    }

    @Test
    void everyEntryHasANonBlankSpecificReason() {
        for (MetadataEntry entry : MetadataEntry.values()) {
            assertNotNull(entry.reason(), entry + " has no reason");
            assertTrue(entry.reason().length() >= 80,
                    entry + " reason is too short to be an audit: " + entry.reason());
        }
    }

    @Test
    void allReasonsAreUnique() {
        List<String> reasons = Arrays.stream(MetadataEntry.values()).map(MetadataEntry::reason).toList();
        assertEquals(reasons.size(), Set.copyOf(reasons).size(), "two entries share a reason — the matrix lost precision");
    }

    @Test
    void everyVerdictIsEitherSyncedOrExcluded() {
        for (MetadataEntry entry : MetadataEntry.values()) {
            assertNotNull(entry.verdict(), entry + " has no verdict");
        }
        assertEquals(2, Arrays.stream(MetadataEntry.values())
                .filter(e -> e.verdict() == MetadataEntry.Verdict.SYNCED).count());
    }

    @Test
    void theExcludedSetIsFullyEnumeratedAndMustBeReviewedWhenExtended() {
        // exact audit snapshot: adding an entry to the matrix means updating this test on purpose
        Set<String> expected = Set.of(
                "GLOWING", "INVISIBILITY", "INVULNERABILITY", "FREEZE_TICKS", "COLLIDABLE",
                "CUSTOM_NAME", "PLAYER_LIST_ORDER", "TABLIST_HEADER_FOOTER", "LOCALE", "CLIENT_BRAND",
                "DEATH_SCREEN_SCORE", "TEAM_DISPLAY_NAME",
                "BUKKIT_METADATA", "SCOREBOARD_TEAMS", "PERSISTENT_DATA_CONTAINER",
                "RESPAWN_LOCATION", "LAST_DEATH_LOCATION", "COMPASS_TARGET",
                "FLIGHT_ABILITIES", "MOVEMENT_FLAGS", "SKIN",
                "VITALS", "EXPERIENCE", "INVENTORY", "PROGRESSION");
        assertEquals(expected, MetadataEntry.excluded().stream().map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void excludedAndApplicableSetsAreDisjoint() {
        Set<MetadataEntry> applicable = Set.copyOf(MetadataEntry.applicable());
        Set<MetadataEntry> excluded = Set.copyOf(MetadataEntry.excluded());
        assertTrue(applicable.stream().noneMatch(excluded::contains));
    }

    @Test
    void excludedEntriesAreNeverWireKeysOfThePayload() {
        // the payload schema only knows the two synced keys; an excluded entry sneaking in
        // as a wire key would mean someone wired it into the format
        for (MetadataEntry entry : MetadataEntry.excluded()) {
            assertNull(entry.wireKey(), entry + " leaked into the wire contract");
        }
    }
}
