package lol.candyriya.continuum.paper.transfer;

import lol.candyriya.continuum.api.snapshot.SyncResult;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * The outcome of the pre-transfer snapshot request.
 *
 * <p>{@code snapshotPersisted} is the one bit the transfer flow needs: velocity
 * must not move the player until it is true. {@code result} carries the full
 * verdict of the underlying capture ({@code SUCCESS}/{@code PARTIAL}/{@code FAILED}),
 * {@code snapshotId} identifies the persisted row when one was written, and
 * {@code detail} is an English explanation for logs and operator output when
 * something failed.</p>
 *
 * @param snapshotPersisted true when the pre-transfer snapshot reached the database
 * @param result            the capture verdict
 * @param snapshotId        the persisted history row id, or null when nothing was persisted
 * @param completedAt       when the capture finished
 * @param detail            why the snapshot is not usable, or null on success
 */
public record TransferCaptureOutcome(boolean snapshotPersisted,
                                     SyncResult result,
                                     @Nullable Long snapshotId,
                                     Instant completedAt,
                                     @Nullable String detail) {

    public TransferCaptureOutcome {
        if (snapshotPersisted && snapshotId != null && snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1 when present, got " + snapshotId);
        }
    }
}
