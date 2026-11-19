package lol.candyriya.continuum.paper.modules.pdc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget: per-key skips, total abort, accumulation across offers.
 * Sizes are decoded footprints; boundary values sit exactly on the cap.
 */
class PdcCaptureLimiterTest {

    @Test
    void capturesUnderBothCaps() {
        var limiter = PdcCaptureLimiter.defaults();
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(100));
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(200));
        assertEquals(300, limiter.totalBytes());
        assertEquals(2, limiter.capturedCount());
    }

    @Test
    void valueExactlyAtPerKeyCapIsCaptured() {
        var limiter = new PdcCaptureLimiter(64, 1024);
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(64));
    }

    @Test
    void valueOverPerKeyCapIsSkippedWithoutSpendingBudget() {
        var limiter = new PdcCaptureLimiter(64, 1024);
        assertEquals(PdcCaptureLimiter.Verdict.SKIP_OVERSIZE, limiter.offer(65));
        assertEquals(0, limiter.totalBytes());
        assertEquals(0, limiter.capturedCount());
        // a later small value still fits
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(10));
    }

    @Test
    void totalExactlyAtCapIsCaptured() {
        var limiter = new PdcCaptureLimiter(1024, 1000);
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(600));
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(400));
        assertEquals(1000, limiter.totalBytes());
    }

    @Test
    void totalOverCapAbortsTheSnapshot() {
        var limiter = new PdcCaptureLimiter(1024, 1000);
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(600));
        assertEquals(PdcCaptureLimiter.Verdict.ABORT_SNAPSHOT_TOO_LARGE, limiter.offer(401));
        // the aborting offer does not consume budget - the snapshot is refused anyway
        assertEquals(600, limiter.totalBytes());
    }

    @Test
    void accumulationHappensAcrossManyOffers() {
        var limiter = new PdcCaptureLimiter(100, 250);
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(100));
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(100));
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(50));
        assertEquals(PdcCaptureLimiter.Verdict.ABORT_SNAPSHOT_TOO_LARGE, limiter.offer(1));
    }

    @Test
    void perKeyCapDoesNotBindTotalWhenTotalIsSmaller() {
        // a 64 KiB value fits the default per-key cap but not a tiny total cap
        var limiter = new PdcCaptureLimiter(PdcLimits.PER_KEY_BYTES, 1000);
        assertEquals(PdcCaptureLimiter.Verdict.ABORT_SNAPSHOT_TOO_LARGE, limiter.offer(2048));
    }

    @Test
    void rejectsNegativeSizesAndNonPositiveCaps() {
        var limiter = PdcCaptureLimiter.defaults();
        assertThrows(IllegalArgumentException.class, () -> limiter.offer(-1));
        assertThrows(IllegalArgumentException.class, () -> new PdcCaptureLimiter(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PdcCaptureLimiter(100, 0));
    }

    @Test
    void defaultCapsAre64KiPerKeyAndOneMiBTotal() {
        assertEquals(64L * 1024, PdcLimits.PER_KEY_BYTES);
        assertEquals(1024L * 1024, PdcLimits.TOTAL_BYTES);
    }

    @Test
    void fullBudgetExhaustionAbortsAndDoesNotOvercount() {
        var limiter = new PdcCaptureLimiter(10, 20);
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(10));
        assertEquals(PdcCaptureLimiter.Verdict.CAPTURE, limiter.offer(10));
        assertTrue(limiter.remainingBytes() == 0);
        assertEquals(PdcCaptureLimiter.Verdict.ABORT_SNAPSHOT_TOO_LARGE, limiter.offer(1));
        assertEquals(20, limiter.totalBytes());
    }
}
