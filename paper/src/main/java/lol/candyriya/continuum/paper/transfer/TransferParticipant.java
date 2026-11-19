package lol.candyriya.continuum.paper.transfer;

import lol.candyriya.continuum.api.report.CaptureReport;
import lol.candyriya.continuum.api.snapshot.SnapshotReason;
import lol.candyriya.continuum.api.snapshot.SyncResult;
import lol.candyriya.continuum.paper.orchestration.SnapshotManager;

import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The backend side of a server switch — the part that happens <em>before</em>
 * the player leaves this backend.
 *
 * <p>On a pre-transfer snapshot request (arriving from the proxy via plugin
 * messaging once that seam lands; callable directly today) the backend forces
 * a full capture — bypassing every optimization — and reports the outcome back
 * through {@link #prepareTransfer(Player)}. On the target backend the restore
 * runs through the join gate as usual; this class is only the departure
 * half.</p>
 *
 * <p>Contract: the returned future completes with the outcome instead of
 * failing exceptionally for expected failure modes (storage down, a required
 * module failed, the capture was vetoed by an event listener). It completes
 * exceptionally only for internal bugs of the pipeline itself.</p>
 */
public final class TransferParticipant {

    private final SnapshotManager snapshots;

    public TransferParticipant(SnapshotManager snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /**
     * Forces a full capture of the player NOW and reports whether the
     * pre-transfer snapshot reached the database.
     */
    public CompletableFuture<TransferCaptureOutcome> prepareTransfer(Player player) {
        Objects.requireNonNull(player, "player");
        return snapshots.capture(player, SnapshotReason.TRANSFER)
                .handle((report, err) -> {
                    Throwable cause = unwrap(err);
                    if (cause != null) {
                        return new TransferCaptureOutcome(false, SyncResult.FAILED, null, Instant.now(),
                                "capture did not complete: " + cause.getMessage());
                    }
                    boolean persisted = report.snapshotIdIfPersisted().isPresent();
                    SyncResult verdict = report.result();
                    return new TransferCaptureOutcome(
                            persisted,
                            verdict,
                            report.snapshotIdIfPersisted().orElse(null),
                            report.completedAt(),
                            persisted ? null : failureDetail(verdict));
                });
    }

    private static String failureDetail(SyncResult verdict) {
        return switch (verdict) {
            case SUCCESS -> "the capture reported success but nothing was persisted (vetoed, or no modules enabled)";
            case PARTIAL -> "the snapshot was not persisted; a required module failed before the write";
            case FAILED -> "the snapshot was not persisted; see the capture report for the failing module";
        };
    }

    private static Throwable unwrap(Throwable err) {
        if (err == null) {
            return null;
        }
        if (err instanceof CompletionException || err instanceof ExecutionException) {
            return err.getCause() != null ? err.getCause() : err;
        }
        return err;
    }
}
