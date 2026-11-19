package lol.candyriya.continuum.paper.modules.effects;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectsValidationTest {

    private static EffectEntry example(String type) {
        return new EffectEntry(type, 100, 0, false, true, true, false);
    }

    private static EffectsPayload payload(int format, long capturedAt, List<EffectEntry> entries) {
        return new EffectsPayload(format, capturedAt, entries);
    }

    @Test
    void currentFormatIsAccepted() {
        ValidationResult result = EffectsValidation.check(
                payload(EffectsPayload.FORMAT, 1_700_000_000_000L, List.of(example("minecraft:speed"))));
        assertTrue(result.valid());
    }

    @Test
    void emptyEffectsListIsAccepted() {
        // a player with no active effects captures an empty list; that is a fine payload
        ValidationResult result = EffectsValidation.check(payload(EffectsPayload.FORMAT, 0L, List.of()));
        assertTrue(result.valid());
    }

    @Test
    void olderFormatIsRejected() {
        ValidationResult result = EffectsValidation.check(payload(0, 1_700_000_000_000L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"));
    }

    @Test
    void newerFormatIsRejected() {
        ValidationResult result = EffectsValidation.check(payload(EffectsPayload.FORMAT + 1, 1_700_000_000_000L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"));
    }

    @Test
    void negativeCaptureTimestampIsRejected() {
        ValidationResult result = EffectsValidation.check(payload(EffectsPayload.FORMAT, -1L, List.of()));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("captured_at_epoch_milli"));
    }

    @Test
    void exactlyMaxEffectsIsAccepted() {
        var entries = new ArrayList<EffectEntry>();
        for (int i = 0; i < EffectsPayload.MAX_EFFECTS; i++) {
            entries.add(example("minecraft:effect_" + i));
        }
        assertTrue(EffectsValidation.check(payload(EffectsPayload.FORMAT, 1L, entries)).valid());
    }

    @Test
    void beyondMaxEffectsIsRejected() {
        var entries = new ArrayList<EffectEntry>();
        for (int i = 0; i <= EffectsPayload.MAX_EFFECTS; i++) {
            entries.add(example("minecraft:effect_" + i));
        }
        ValidationResult result = EffectsValidation.check(payload(EffectsPayload.FORMAT, 1L, entries));
        assertFalse(result.valid());
        assertTrue(result.reason().contains(String.valueOf(EffectsPayload.MAX_EFFECTS)));
    }

    @Test
    void invalidReasonIsNeverBlank() {
        ValidationResult result = EffectsValidation.check(payload(-1, -1L, List.of()));
        assertFalse(result.valid());
        assertFalse(result.reason() == null || result.reason().isBlank());
    }
}
