package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restore planning is the last policy gate before a live container is touched:
 * deny-list, key legality, caps and budget all re-apply here.
 */
class PdcRestorePlannerTest {

    private static PdcEntry stringEntry(String key, String value) {
        return new PdcEntry(key, PdcValueType.STRING, value);
    }

    @Test
    void allowedEntriesBecomeActionsInPayloadOrder() {
        var payload = PdcPayload.of(List.of(
                stringEntry("quests:flags", "a"),
                stringEntry("cosmetics:trail", "b"),
                stringEntry("continuum:grant", "c")));
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(3, plan.actions().size());
        assertEquals(List.of("quests:flags", "cosmetics:trail", "continuum:grant"),
                plan.actions().stream().map(PdcEntry::key).toList());
        assertEquals(3, plan.totalEntries());
    }

    @Test
    void deniedNamespacesAreSkipped() {
        var payload = PdcPayload.of(List.of(
                stringEntry("quests:flags", "keep"),
                stringEntry("security:totp_secret", "drop"),
                stringEntry("auth:session", "drop"),
                stringEntry("2fa:pending", "drop"),
                stringEntry("login:token", "drop"),
                stringEntry("verification:code", "drop"),
                stringEntry("manual:notes", "drop"),
                stringEntry("authy:enrolled", "drop")));
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(List.of("quests:flags"), plan.actions().stream().map(PdcEntry::key).toList());
        assertEquals(7, plan.skippedDenied());
    }

    @Test
    void illegalKeysAreSkippedNotFatal() {
        var payload = PdcPayload.of(List.of(
                stringEntry("quests:flags", "fine"),
                stringEntry("NO-GOOD-KEY", "nope"),
                stringEntry("white space:key", "nope")));
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(1, plan.actions().size());
        assertEquals(2, plan.skippedIllegalKey());
    }

    @Test
    void decodableBinaryEntriesPlanFine() {
        // entry construction guarantees valid base64, so the planner's undecodable branch is
        // pure belt-and-braces; this pins that the happy binary path lands in actions
        var payload = PdcPayload.of(List.of(
                stringEntry("quests:flags", "fine"),
                new PdcEntry("blobs:data", PdcValueType.BYTE_ARRAY, PdcCodec.encodeBase64(new byte[]{1, 2, 3}))));
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(2, plan.actions().size());
        assertEquals(0, plan.skippedUndecodable());
    }

    @Test
    void perValueCapIsReenforcedAtRestore() {
        // a string right at the cap passes; validate() would already have rejected anything
        // bigger, so this is the restore-side belt to the validator's braces
        var atCap = "x".repeat((int) PdcLimits.PER_KEY_BYTES);
        var payload = PdcPayload.of(List.of(stringEntry("blobs:big", atCap)));
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(1, plan.actions().size());
        assertEquals(0, plan.skippedTooLarge());
    }

    @Test
    void totalBudgetIsReenforcedAtRestore() {
        var entries = new ArrayList<PdcEntry>();
        // fill the budget with 17 x 64 KiB-1 strings (0xFFFF) then tip it over
        String chunk = "x".repeat((int) PdcLimits.PER_KEY_BYTES);
        int fits = (int) (PdcLimits.TOTAL_BYTES / PdcLimits.PER_KEY_BYTES); // 16 chunks of 64 KiB
        for (int i = 0; i < fits; i++) {
            entries.add(stringEntry("blobs:chunk_" + i, chunk));
        }
        entries.add(stringEntry("blobs:overflow", "y"));
        var payload = PdcPayload.of(entries);
        var plan = PdcRestorePlanner.plan(payload);

        assertEquals(fits, plan.actions().size());
        assertEquals(1, plan.skippedBudgetExhausted());
    }

    @Test
    void mixedSkipsAllAddUp() {
        var entries = new ArrayList<PdcEntry>();
        entries.add(stringEntry("quests:flags", "fine"));
        entries.add(stringEntry("security:secret", "denied"));
        entries.add(stringEntry("BROKEN KEY", "illegal"));
        var plan = PdcRestorePlanner.plan(payloadOf(entries));

        assertEquals(1, plan.actions().size());
        assertEquals(1, plan.skippedDenied());
        assertEquals(1, plan.skippedIllegalKey());
        assertEquals(3, plan.totalEntries());
    }

    @Test
    void emptyPayloadPlansNothing() {
        var plan = PdcRestorePlanner.plan(PdcPayload.of(List.of()));
        assertTrue(plan.actions().isEmpty());
        assertEquals(0, plan.totalEntries());
    }

    private static PdcPayload payloadOf(List<PdcEntry> entries) {
        return new PdcPayload(PdcPayload.FORMAT, entries);
    }
}
