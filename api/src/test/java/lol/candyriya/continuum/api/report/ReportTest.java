package lol.candyriya.continuum.api.report;

import lol.candyriya.continuum.api.module.InvalidModuleIdException;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReportTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    // ---- ModuleResult

    @Test
    void okRow() {
        ModuleResult ok = ModuleResult.ok("continuum.inventory", 12);

        assertEquals(ModuleStatus.OK, ok.status());
        assertEquals(ModuleCodes.OK, ok.code());
        assertEquals(12, ok.durationMillis());
        assertFalse(ok.failed());
        assertNull(ok.detail());
    }

    @Test
    void warnRow() {
        ModuleResult warn = ModuleResult.warn("continuum.inventory", ModuleCodes.WARNING, "truncated extras", 30);

        assertEquals(ModuleStatus.WARN, warn.status());
        assertEquals("truncated extras", warn.detail());
        assertFalse(warn.failed());
    }

    @Test
    void failedRow() {
        ModuleResult failed = ModuleResult.failed("continuum.vitals", ModuleCodes.RESTORE_FAILED, "boom", 99);

        assertEquals(ModuleStatus.FAILED, failed.status());
        assertTrue(failed.failed());
        assertEquals("boom", failed.detail());
    }

    @Test
    void skippedRowHasZeroDuration() {
        ModuleResult skipped = ModuleResult.skipped("continuum.recipes", ModuleCodes.DEPENDENCY_MISSING, "recipes needs inventory");

        assertEquals(ModuleStatus.SKIPPED, skipped.status());
        assertEquals(0, skipped.durationMillis());
        assertFalse(skipped.failed());
    }

    @Test
    void blankCodeIsRejectedEverywhere() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleResult("continuum.vitals", ModuleStatus.OK, "  ", null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleResult.warn("continuum.vitals", "", "d", 0));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleResult.failed("continuum.vitals", " ", "d", 0));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleResult.skipped("continuum.vitals", "", "d"));
    }

    @Test
    void negativeDurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleResult("continuum.vitals", ModuleStatus.OK, ModuleCodes.OK, null, -1));
    }

    @Test
    void malformedModuleIdIsRejected() {
        assertThrows(InvalidModuleIdException.class,
                () -> new ModuleResult("Bad.Id", ModuleStatus.OK, ModuleCodes.OK, null, 0));
    }

    // ---- SyncReport defaults (through both concrete reports)

    private static RestoreReport restoreReport(List<ModuleResult> rows) {
        return new RestoreReport(PLAYER, 7, SyncResult.SUCCESS, rows, NOW);
    }

    @Test
    void moduleLookupFindsTheRow() {
        RestoreReport report = restoreReport(List.of(
                ModuleResult.ok("continuum.inventory", 5),
                ModuleResult.failed("continuum.vitals", ModuleCodes.RESTORE_FAILED, null, 9)));

        Optional<ModuleResult> found = report.module("continuum.vitals");
        Optional<ModuleResult> missing = report.module("continuum.recipes");

        assertTrue(found.isPresent());
        assertEquals(ModuleCodes.RESTORE_FAILED, found.get().code());
        assertTrue(missing.isEmpty());
    }

    @Test
    void moduleLookupWithNullIdThrows() {
        RestoreReport report = restoreReport(List.of());

        assertThrows(NullPointerException.class, () -> report.module(null));
    }

    @Test
    void failedModulesFiltersRows() {
        RestoreReport report = restoreReport(List.of(
                ModuleResult.ok("continuum.inventory", 5),
                ModuleResult.failed("continuum.vitals", ModuleCodes.TIMEOUT, null, 9),
                ModuleResult.failed("continuum.effects", ModuleCodes.RESTORE_FAILED, null, 9)));

        assertEquals(2, report.failedModules().size());
        assertEquals(List.of("continuum.vitals", "continuum.effects"),
                report.failedModules().stream().map(ModuleResult::moduleId).toList());
    }

    @Test
    void isCompleteSuccessReflectsTheVerdict() {
        assertTrue(restoreReport(List.of()).isCompleteSuccess());
        RestoreReport partial = new RestoreReport(PLAYER, 7, SyncResult.PARTIAL, List.of(), NOW);
        assertFalse(partial.isCompleteSuccess());
    }

    // ---- CaptureReport / RestoreReport validation

    @Test
    void captureReportAllowsMissingSnapshotId() {
        CaptureReport noSnapshot = new CaptureReport(PLAYER, null, SnapshotReason.AUTOSAVE,
                SyncResult.FAILED, List.of(ModuleResult.failed("continuum.vitals", ModuleCodes.CAPTURE_FAILED, null, 1)), NOW);
        CaptureReport persisted = new CaptureReport(PLAYER, 100L, SnapshotReason.TRANSFER,
                SyncResult.SUCCESS, List.of(), NOW);

        assertTrue(noSnapshot.snapshotIdIfPersisted().isEmpty());
        assertEquals(100L, persisted.snapshotIdIfPersisted().orElseThrow());
        assertEquals(SnapshotReason.TRANSFER, persisted.reason());
    }

    @Test
    void restoreReportRejectsInvalidSnapshotId() {
        assertThrows(IllegalArgumentException.class,
                () -> new RestoreReport(PLAYER, 0, SyncResult.SUCCESS, List.of(), NOW));
    }

    @Test
    void modulesAreCopiedAndUnmodifiable() {
        java.util.List<ModuleResult> rows = new java.util.ArrayList<>(List.of(ModuleResult.ok("continuum.vitals", 1)));
        RestoreReport report = restoreReport(rows);

        rows.add(ModuleResult.ok("continuum.inventory", 1));

        assertEquals(1, report.modules().size());
        assertThrows(UnsupportedOperationException.class,
                () -> report.modules().add(ModuleResult.ok("continuum.recipes", 1)));
    }

    @Test
    void nullComponentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> new RestoreReport(null, 1, SyncResult.SUCCESS, List.of(), NOW));
        assertThrows(NullPointerException.class,
                () -> new RestoreReport(PLAYER, 1, null, List.of(), NOW));
        assertThrows(NullPointerException.class,
                () -> new RestoreReport(PLAYER, 1, SyncResult.SUCCESS, null, NOW));
        assertThrows(NullPointerException.class,
                () -> new RestoreReport(PLAYER, 1, SyncResult.SUCCESS, List.of(), null));
        assertThrows(NullPointerException.class,
                () -> new CaptureReport(null, 1L, SnapshotReason.API, SyncResult.SUCCESS, List.of(), NOW));
        assertThrows(NullPointerException.class,
                () -> new CaptureReport(PLAYER, 1L, null, SyncResult.SUCCESS, List.of(), NOW));
    }
}
