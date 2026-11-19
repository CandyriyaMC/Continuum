package lol.candyriya.continuum.paper.modules.pdc;

import lol.candyriya.continuum.api.module.ValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-restore gate: format rejection, duplicate keys, size caps.
 * Never throws, always a reason in English.
 */
class PdcValidationTest {

    private static PdcPayload payload(int format, List<PdcEntry> entries) {
        return new PdcPayload(format, entries);
    }

    @Test
    void aCleanPayloadValidates() {
        var payload = PdcPayload.of(List.of(
                new PdcEntry("quests:flags", PdcValueType.LONG, 1),
                new PdcEntry("cosmetics:trail", PdcValueType.STRING, "ember"),
                new PdcEntry("rpg:class", PdcValueType.BYTE_ARRAY, PdcCodec.encodeBase64(new byte[]{9}))));
        ValidationResult result = PdcValidation.validate(payload);
        assertTrue(result.valid(), () -> "expected ok, got: " + result.reason());
    }

    @Test
    void emptyPayloadValidates() {
        assertTrue(PdcValidation.validate(PdcPayload.of(List.of())).valid());
    }

    @Test
    void unknownFormatIsRejected() {
        var payload = payload(2, List.of(new PdcEntry("quests:flags", PdcValueType.LONG, 1)));
        ValidationResult result = PdcValidation.validate(payload);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"), result.reason());
    }

    @Test
    void zeroAndNegativeFormatsAreRejected() {
        assertFalse(PdcValidation.validate(payload(0, List.of())).valid());
        assertFalse(PdcValidation.validate(payload(-1, List.of())).valid());
    }

    @Test
    void duplicateKeysAreRejected() {
        var payload = payload(PdcPayload.FORMAT, List.of(
                new PdcEntry("quests:flags", PdcValueType.LONG, 1),
                new PdcEntry("quests:flags", PdcValueType.STRING, "two")));
        ValidationResult result = PdcValidation.validate(payload);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("duplicate"), result.reason());
    }

    @Test
    void illegalKeysAreRejected() {
        var payload = payload(PdcPayload.FORMAT, List.of(
                new PdcEntry("NOT A KEY", PdcValueType.LONG, 1)));
        ValidationResult result = PdcValidation.validate(payload);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("namespaced key"), result.reason());
    }

    @Test
    void totalBudgetIsEnforcedAcrossEntries() {
        var entries = new java.util.ArrayList<PdcEntry>();
        String chunk = "x".repeat((int) PdcLimits.PER_KEY_BYTES);
        int fits = (int) (PdcLimits.TOTAL_BYTES / PdcLimits.PER_KEY_BYTES); // exactly at the cap: ok
        for (int i = 0; i < fits; i++) {
            entries.add(new PdcEntry("blobs:chunk_" + i, PdcValueType.STRING, chunk));
        }
        assertTrue(PdcValidation.validate(payload(PdcPayload.FORMAT, entries)).valid());

        // one more entry tips it over
        entries.add(new PdcEntry("blobs:overflow", PdcValueType.STRING, "y"));
        ValidationResult result = PdcValidation.validate(payload(PdcPayload.FORMAT, entries));
        assertFalse(result.valid());
        assertTrue(result.reason().contains("total"), result.reason());
    }

    @Test
    void perEntryBase64DeeplyNestedContainerSurvivesValidation() {
        // a nested container inside a nested container, encoded bytes only - the validator
        // measures the footprint, it does not parse NBT
        byte[] fakeNbt = new byte[1024];
        for (int i = 0; i < fakeNbt.length; i++) {
            fakeNbt[i] = (byte) (i * 37);
        }
        var payload = payload(PdcPayload.FORMAT, List.of(
                new PdcEntry("rpg:state", PdcValueType.TAG_CONTAINER, PdcCodec.encodeBase64(fakeNbt))));
        assertTrue(PdcValidation.validate(payload).valid());
    }
}
