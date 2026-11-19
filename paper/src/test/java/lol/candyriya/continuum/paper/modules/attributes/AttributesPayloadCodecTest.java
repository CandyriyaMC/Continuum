package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributesPayloadCodecTest {

    private static final Instant AT = Instant.parse("2026-11-19T12:34:56.789Z");

    private static CapturedModifier modifier(String key, double amount, String operation, String slotGroup) {
        return new CapturedModifier(key, amount, operation, slotGroup);
    }

    private static CapturedAttribute attribute(String key, double base, CapturedModifier... modifiers) {
        return new CapturedAttribute(key, base, List.of(modifiers));
    }

    @Test
    void encodeProducesTheDocumentedWireShapeAndKeyOrder() {
        var captured = List.of(
                attribute("minecraft:max_health", 20.0,
                        modifier("continuum:headstart", 2.0, "ADD_NUMBER", "any")));

        Map<String, Object> encoded = AttributesPayloadCodec.encode(captured, AT);

        assertEquals(List.of("format", "capturedAt", "attributes"), List.copyOf(encoded.keySet()));
        assertInstanceOf(Integer.class, encoded.get("format"));
        assertEquals(1, encoded.get("format"));
        assertEquals(AT.toString(), encoded.get("capturedAt"));

        var attributes = (List<?>) encoded.get("attributes");
        assertEquals(1, attributes.size());
        var entry = (Map<?, ?>) attributes.getFirst();
        assertEquals(List.of("key", "base", "modifiers"), List.copyOf(entry.keySet()));
        assertEquals("minecraft:max_health", entry.get("key"));
        assertEquals(20.0, entry.get("base"));

        var modifiers = (List<?>) entry.get("modifiers");
        var modifier = (Map<?, ?>) modifiers.getFirst();
        assertEquals(List.of("key", "amount", "operation", "slotGroup"), List.copyOf(modifier.keySet()));
        assertEquals("continuum:headstart", modifier.get("key"));
        assertEquals(2.0, modifier.get("amount"));
        assertEquals("ADD_NUMBER", modifier.get("operation"));
        assertEquals("any", modifier.get("slotGroup"));
    }

    @Test
    void encodingIsDeterministicRegardlessOfModifierOrder() {
        var unsorted = List.of(
                attribute("minecraft:max_health", 20.0,
                        modifier("continuum:b", 1.0, "ADD_NUMBER", "any"),
                        modifier("continuum:a", 1.0, "ADD_NUMBER", "any"),
                        modifier("continuum:a", 0.5, "ADD_SCALAR", "mainhand")));
        var sorted = List.of(
                attribute("minecraft:max_health", 20.0,
                        modifier("continuum:a", 0.5, "ADD_SCALAR", "mainhand"),
                        modifier("continuum:a", 1.0, "ADD_NUMBER", "any"),
                        modifier("continuum:b", 1.0, "ADD_NUMBER", "any")));

        Map<String, Object> first = AttributesPayloadCodec.encode(unsorted, AT);
        Map<String, Object> second = AttributesPayloadCodec.encode(sorted, AT);

        assertEquals(second, first, "modifier sort must make the encoding order-independent");
    }

    @Test
    void payloadRoundTripsThroughTheCodec() {
        var captured = List.of(
                attribute("minecraft:max_health", 20.0,
                        modifier("continuum:headstart", 2.0, "ADD_NUMBER", "any"),
                        modifier("continuum:buff", 0.25, "MULTIPLY_SCALAR_1", "mainhand")),
                attribute("minecraft:movement_speed", 0.13),
                attribute("minecraft:attack_damage", 1.5));

        DecodedPayload decoded = AttributesPayloadCodec.decode(AttributesPayloadCodec.encode(captured, AT));

        assertEquals(captured, decoded.attributes());
        assertEquals(AT.toString(), decoded.capturedAt());
        assertTrue(decoded.warnings().isEmpty(), "a clean roundtrip must not warn: " + decoded.warnings());
        assertEquals(captured.size(), decoded.rawEntries());
    }

    @Test
    void unknownModifierOperationIsSkippedWithAWarningNotAnException() {
        var raw = Map.<String, Object>of(
                "format", 1,
                "capturedAt", AT.toString(),
                "attributes", List.of(Map.of(
                        "key", "minecraft:max_health",
                        "base", 20.0,
                        "modifiers", List.of(
                                Map.of("key", "continuum:ok", "amount", 1.0, "operation", "ADD_NUMBER", "slotGroup", "any"),
                                Map.of("key", "continuum:bogus", "amount", 1.0, "operation", "DIVIDE", "slotGroup", "any")))));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        assertEquals(1, decoded.attributes().size());
        var mods = decoded.attributes().getFirst().modifiers();
        assertEquals(1, mods.size());
        assertEquals("continuum:ok", mods.getFirst().key());
        assertTrue(decoded.warnings().stream().anyMatch(w -> w.contains("DIVIDE")),
                "unknown operation must be named in the warnings: " + decoded.warnings());
    }

    @Test
    void entriesWithBrokenKeysOrBasesAreDroppedIndividually() {
        var raw = Map.<String, Object>of(
                "format", 1,
                "attributes", List.of(
                        Map.of("key", "minecraft:luck", "base", 3.0, "modifiers", List.of()),
                        Map.of("key", "no-namespace-key", "base", 1.0),
                        Map.of("key", "minecraft:armor", "base", "not-a-number"),
                        "garbage"));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        assertEquals(List.of("minecraft:luck"),
                decoded.attributes().stream().map(CapturedAttribute::key).toList());
        assertEquals(4, decoded.rawEntries());
        assertTrue(decoded.warnings().size() >= 3);
    }

    @Test
    void missingSlotGroupDefaultsToAny() {
        var raw = Map.<String, Object>of(
                "format", 1,
                "attributes", List.of(Map.of(
                        "key", "minecraft:max_health",
                        "base", 20.0,
                        "modifiers", List.of(Map.of(
                                "key", "continuum:mod",
                                "amount", 1.0,
                                "operation", "ADD_NUMBER")))));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        assertEquals("any", decoded.attributes().getFirst().modifiers().getFirst().slotGroup());
    }

    @Test
    void duplicateModifierKeysResolveLastWins() {
        var raw = Map.<String, Object>of(
                "format", 1,
                "attributes", List.of(Map.of(
                        "key", "minecraft:max_health",
                        "base", 20.0,
                        "modifiers", List.of(
                                Map.of("key", "continuum:dup", "amount", 1.0, "operation", "ADD_NUMBER"),
                                Map.of("key", "continuum:dup", "amount", 9.0, "operation", "ADD_SCALAR")))));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        var mods = decoded.attributes().getFirst().modifiers();
        assertEquals(1, mods.size());
        assertEquals(9.0, mods.getFirst().amount());
        assertEquals("ADD_SCALAR", mods.getFirst().operation());
    }

    @Test
    void duplicateAttributeEntriesResolveLastWins() {
        var raw = Map.<String, Object>of(
                "format", 1,
                "attributes", List.of(
                        Map.of("key", "minecraft:luck", "base", 1.0),
                        Map.of("key", "minecraft:luck", "base", 7.0)));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        assertEquals(1, decoded.attributes().size());
        assertEquals(7.0, decoded.attributes().getFirst().base());
    }

    @Test
    void newerFormatIsToleratedWithAWarning() {
        var raw = Map.<String, Object>of(
                "format", 99,
                "attributes", List.of(Map.of("key", "minecraft:luck", "base", 1.0)));

        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);

        assertEquals(1, decoded.attributes().size());
        assertTrue(decoded.warnings().stream().anyMatch(w -> w.contains("99")));
    }

    @Test
    void structurallyBrokenAttributesNodeIsRefusedByValidation() {
        var raw = Map.<String, Object>of("format", 1, "attributes", "nope");

        assertFalse(AttributesPayloadCodec.validate(raw).valid());
        DecodedPayload decoded = AttributesPayloadCodec.decode(raw);
        assertEquals(-1, decoded.rawEntries());
        assertTrue(decoded.attributes().isEmpty());
    }

    @Test
    void validationRejectsGarbageAndAcceptsGoodPayloads() {
        assertFalse(AttributesPayloadCodec.validate(null).valid());
        assertFalse(AttributesPayloadCodec.validate(Map.of()).valid()); // missing format
        assertFalse(AttributesPayloadCodec.validate(Map.of("format", 1.5, "attributes", List.of())).valid());
        assertFalse(AttributesPayloadCodec.validate(Map.of("format", 1, "attributes", List.of(
                Map.of("key", "bogus", "base", 1.0)))).valid()); // entry unusable

        assertTrue(AttributesPayloadCodec.validate(Map.of("format", 1, "attributes", List.of())).valid()); // empty is legal
        assertTrue(AttributesPayloadCodec.validate(
                Map.of("format", 2, "attributes", List.of(Map.of("key", "minecraft:luck", "base", 1.0)))).valid());
        assertTrue(AttributesPayloadCodec.validate(
                Map.of("format", 1, "attributes", List.of(Map.of("key", "minecraft:luck", "base", 1.0)))).valid());
    }
}
