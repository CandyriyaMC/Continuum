package lol.candyriya.continuum.paper.modules.attributes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreVerifierTest {

    private static CapturedAttribute attribute(String key, double base) {
        return new CapturedAttribute(key, base, List.of());
    }

    @Test
    void basesWithinEpsilonAreClean() {
        Function<String, OptionalDouble> reReader = key -> OptionalDouble.of(20.0 + 5e-7);

        var drift = RestoreVerifier.verify(List.of(attribute("minecraft:max_health", 20.0)), reReader);

        assertTrue(drift.isEmpty());
    }

    @Test
    void basesBeyondEpsilonAreReported() {
        Function<String, OptionalDouble> reReader = key -> OptionalDouble.of(19.0);

        var drift = RestoreVerifier.verify(List.of(attribute("minecraft:max_health", 20.0)), reReader);

        assertEquals(1, drift.size());
        assertEquals("minecraft:max_health", drift.getFirst().attributeKey());
        assertEquals(20.0, drift.getFirst().expected());
        assertEquals(19.0, drift.getFirst().actual());
    }

    @Test
    void unreadableAttributeIsDriftNotSilence() {
        Function<String, OptionalDouble> reReader = key -> OptionalDouble.empty();

        var drift = RestoreVerifier.verify(List.of(attribute("minecraft:luck", 3.0)), reReader);

        assertEquals(1, drift.size());
        assertTrue(Double.isNaN(drift.getFirst().actual()));
    }

    @Test
    void nanOnTheInstanceSideIsDrift() {
        Function<String, OptionalDouble> reReader = key -> OptionalDouble.of(Double.NaN);

        var drift = RestoreVerifier.verify(List.of(attribute("minecraft:luck", 3.0)), reReader);

        assertEquals(1, drift.size());
    }

    @Test
    void epsilonIsExactlyOneMicro() {
        // zero expected keeps the subtraction exact in binary floating point
        Function<String, OptionalDouble> reReader = key -> OptionalDouble.of(1e-6);
        Function<String, OptionalDouble> justOver = key -> OptionalDouble.of(Math.nextAfter(1e-6, 2e-6));

        assertTrue(RestoreVerifier.verify(List.of(attribute("minecraft:max_health", 0.0)), reReader).isEmpty());
        assertEquals(1, RestoreVerifier.verify(List.of(attribute("minecraft:max_health", 0.0)), justOver).size());
    }

    @Test
    void emptyExpectationsAreClean() {
        assertTrue(RestoreVerifier.verify(List.of(), key -> OptionalDouble.of(1.0)).isEmpty());
    }
}
