package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.snapshot.SnapshotEnvelope;
import lol.candyriya.continuum.common.snapshot.ModuleSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A player snapshot together with the session metadata the storage layer needs
 * to guard it. Wraps common's serialized envelope; the payload is never
 * modified or reinterpreted here, it round-trips through the storage engine
 * byte-for-byte.
 *
 * <p>{@code uuid} and {@code schemaVersion} are derived from the envelope so
 * the two can never drift apart; they are exposed as accessors for callers
 * that only care about identity.</p>
 *
 * @param envelope     common's snapshot container, never null
 * @param fencingToken the fencing token the snapshot was written under (or the
 *                     historical token when read back from history); never negative
 * @param savedAt      when the row was persisted (UTC), never null
 */
public record PlayerSnapshot(SnapshotEnvelope envelope, long fencingToken, Instant savedAt) {

    public PlayerSnapshot {
        Objects.requireNonNull(envelope, "envelope must not be null");
        if (fencingToken < 0) {
            throw new IllegalArgumentException("fencingToken must be >= 0, got " + fencingToken);
        }
        Objects.requireNonNull(savedAt, "savedAt must not be null");
    }

    /** The owning player. Always {@code envelope.playerId()}, never null. */
    public UUID playerId() {
        return envelope.playerId();
    }

    /** The snapshot schema version. Always {@code envelope.schemaVersion()}, never below 1. */
    public int schemaVersion() {
        return envelope.schemaVersion();
    }

    /** Module ids that participated in the capture, in capture order. Never null, unmodifiable. */
    public List<String> moduleIds() {
        return envelope.modules().stream().map(ModuleSnapshot::moduleId).toList();
    }
}
