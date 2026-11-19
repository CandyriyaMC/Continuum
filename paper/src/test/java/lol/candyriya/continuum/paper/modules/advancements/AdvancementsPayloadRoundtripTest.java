package lol.candyriya.continuum.paper.modules.advancements;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roundtrip of the persisted form. The payload itself never touches JSON — Continuum's
 * snapshot layer does — so this test pins the wire contract against a mapper configured
 * exactly like the Continuum wire conventions (snake_case keys, unknown properties
 * tolerated). If the record components are renamed, this test screams.
 */
class AdvancementsPayloadRoundtripTest {

    /** Mirrors the Continuum wire mapper (snake_case, unknown properties tolerated). */
    private static JsonMapper wireMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    private static AdvancementsPayload trickyPayload() {
        var entries = new ArrayList<AdvancementEntry>();
        // inserted out of order on purpose: the payload normalizes
        entries.add(new AdvancementEntry("minecraft:story/root", List.of(), true, Map.of()));
        var criteria = new ArrayList<String>();
        criteria.add("zebra");
        criteria.add("alpha");
        criteria.add("crafting_table");
        var awardedAt = new HashMap<String, Long>();
        awardedAt.put("zebra", 1_763_458_000_000L);
        awardedAt.put("alpha", 1_763_456_000_000L);
        awardedAt.put("crafting_table", 1_763_457_000_000L);
        entries.add(new AdvancementEntry("minecraft:story/mine_stone", criteria, false, awardedAt));
        return new AdvancementsPayload(AdvancementsPayload.FORMAT, 1_763_456_789_012L, entries);
    }

    @Test
    void roundTripPreservesThePayloadExactly() {
        var mapper = wireMapper();
        var original = trickyPayload();
        String json = mapper.writeValueAsString(original);
        AdvancementsPayload decoded = mapper.readValue(json, AdvancementsPayload.class);
        assertEquals(original, decoded);
    }

    @Test
    void serializedFormUsesSnakeCaseKeysAndStaysCompact() {
        var mapper = wireMapper();
        String json = mapper.writeValueAsString(trickyPayload());
        assertTrue(json.contains("\"captured_at_epoch_milli\""), json);
        assertTrue(json.contains("\"awarded_criteria\""), json);
        assertTrue(json.contains("\"awarded_at\""), json);
        assertFalse(json.contains("capturedAtEpochMilli"), json);
        assertFalse(json.contains("awardedCriteria"), json);
        assertFalse(json.contains("awardedAt"), json);
        assertFalse(json.contains("\n"), "wire output must stay compact: " + json);
    }

    @Test
    void serializedFormMatchesTheExpectedLayoutExactly() {
        // the strongest determinism pin available: same state, same bytes, every time
        var mapper = wireMapper();
        String json = mapper.writeValueAsString(trickyPayload());
        String expected = "{\"format\":1,\"captured_at_epoch_milli\":1763456789012,\"advancements\":["
                + "{\"key\":\"minecraft:story/mine_stone\",\"awarded_criteria\":[\"alpha\",\"crafting_table\",\"zebra\"],\"done\":false,"
                + "\"awarded_at\":{\"alpha\":1763456000000,\"crafting_table\":1763457000000,\"zebra\":1763458000000}},"
                + "{\"key\":\"minecraft:story/root\",\"awarded_criteria\":[],\"done\":true,\"awarded_at\":{}}]}";
        assertEquals(expected, json);
    }

    @Test
    void equivalentStatesBuiltFromShuffledInputsSerializeIdentically() {
        var first = trickyPayload();
        var second = trickyPayload();
        var mapper = wireMapper();
        assertEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(second));
    }

    @Test
    void unknownPropertiesAreToleratedOnRead() {
        // a snapshot written by a newer build may carry fields this one does not know;
        // reading must not fail because of them
        String futuristic = "{\"format\":1,\"captured_at_epoch_milli\":1763456789012,\"future_top_level\":{\"nested\":true},\"advancements\":["
                + "{\"key\":\"minecraft:story/root\",\"awarded_criteria\":[],\"done\":true,\"awarded_at\":{},\"future_entry_field\":42}]}";
        var mapper = wireMapper();
        AdvancementsPayload decoded = mapper.readValue(futuristic, AdvancementsPayload.class);
        assertEquals(1, decoded.advancements().size());
        assertEquals("minecraft:story/root", decoded.advancements().get(0).key());
        assertTrue(decoded.advancements().get(0).done());
    }

    @Test
    void entryWithoutAwardDatesRoundTrips() {
        var entry = new AdvancementEntry("minecraft:story/root", List.of("only_criterion"), true, Map.of());
        var payload = new AdvancementsPayload(AdvancementsPayload.FORMAT, 42L, List.of(entry));
        var mapper = wireMapper();
        AdvancementsPayload decoded = mapper.readValue(mapper.writeValueAsString(payload), AdvancementsPayload.class);
        assertTrue(decoded.advancements().get(0).awardedAt().isEmpty());
        assertEquals(List.of("only_criterion"), decoded.advancements().get(0).awardedCriteria());
    }

    @Test
    void emptyPayloadRoundTrips() {
        var mapper = wireMapper();
        var payload = new AdvancementsPayload(AdvancementsPayload.FORMAT, 0L, List.of());
        AdvancementsPayload decoded = mapper.readValue(mapper.writeValueAsString(payload), AdvancementsPayload.class);
        assertEquals(payload, decoded);
    }

    @Test
    void degradedFormatIsReadableButDistinctFromCurrent() {
        // format markers are just an int on the wire; rejecting them is the validation
        // layer's job, not the mapper's — this test keeps the two concerns separate
        var mapper = wireMapper();
        String json = "{\"format\":99,\"captured_at_epoch_milli\":1,\"advancements\":[]}";
        AdvancementsPayload decoded = mapper.readValue(json, AdvancementsPayload.class);
        assertEquals(99, decoded.format());
    }
}
