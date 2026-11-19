package lol.candyriya.continuum.storage.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata of one stored snapshot, for history listings and rollback selection.
 * Excludes the payload itself; use {@link PlayerStorage#loadSnapshot(long)} to
 * materialize the full record.
 *
 * <p>Instances are immutable. {@link #modules()} is copied and unmodifiable.</p>
 *
 * @param snapshotId    the database id of the snapshot row, {@code >= 1}; rollbacks
 *                      produce a new id, history rows are never rewritten
 * @param playerId      the player whose state this is, never null
 * @param schemaVersion the snapshot-container schema version, {@code >= 1}
 * @param fencingToken  the fencing token the snapshot was written under, {@code >= 0};
 *                      a historical token is not valid for new writes
 * @param savedAt       when the row was persisted (UTC), never null
 * @param reason        why the snapshot was taken; one of the well-known names
 *                      ({@code AUTOSAVE}, {@code TRANSFER}, {@code DISCONNECT},
 *                      {@code SHUTDOWN}, {@code MANUAL}, {@code API}, {@code RECOVERY},
 *                      {@code ROLLBACK}) or a caller-defined value; never null or blank
 * @param sourceBackend the backend that captured the snapshot, never null or blank
 * @param modules       module ids that participated in the capture, capture order; never null
 */
public record SnapshotSummary(long snapshotId,
                              UUID playerId,
                              int schemaVersion,
                              long fencingToken,
                              Instant savedAt,
                              String reason,
                              String sourceBackend,
                              List<String> modules) {

    public SnapshotSummary {
        if (snapshotId < 1) {
            throw new IllegalArgumentException("snapshotId must be >= 1, got " + snapshotId);
        }
        Objects.requireNonNull(playerId, "playerId must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, got " + schemaVersion);
        }
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        Objects.requireNonNull(savedAt, "savedAt must not be null");
        reason = requireReason(reason);
        sourceBackend = requireBackend(sourceBackend);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /** Validates and normalizes a reason: non-blank, at most 32 characters. */
    static String requireReason(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (reason.length() > 32) {
            throw new IllegalArgumentException("reason must be at most 32 characters, got " + reason.length());
        }
        return reason;
    }

    /** Validates and normalizes a backend id: non-blank, at most 64 characters. */
    static String requireBackend(String sourceBackend) {
        Objects.requireNonNull(sourceBackend, "sourceBackend must not be null");
        if (sourceBackend.isBlank()) {
            throw new IllegalArgumentException("sourceBackend must not be blank");
        }
        if (sourceBackend.length() > 64) {
            throw new IllegalArgumentException("sourceBackend must be at most 64 characters, got " + sourceBackend.length());
        }
        return sourceBackend;
    }
}
