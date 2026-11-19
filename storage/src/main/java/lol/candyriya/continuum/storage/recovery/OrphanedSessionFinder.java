package lol.candyriya.continuum.storage.recovery;

import lol.candyriya.continuum.common.error.StorageException;

import java.time.Instant;
import java.util.List;

/**
 * Finds sessions that look abandoned by a crashed backend. The storage API
 * has no query for this (by design — the engines disagree on shape), so each
 * engine ships its own scoped implementation; this is the port both satisfy.
 */
public interface OrphanedSessionFinder {

    /**
     * Up to {@code limit} sessions that are open (not {@code RELEASED} /
     * {@code RECOVERY_REQUIRED}), never ended, and whose lease expired before
     * {@code olderThan}. Oldest lease first, so the longest-dead sessions are
     * handled first.
     *
     * @param olderThan lease horizon; typically {@code Instant.now()}
     * @param limit     maximum rows, 1..1000
     * @throws StorageException when the scan cannot complete
     */
    List<OrphanedSession> findOrphans(Instant olderThan, int limit) throws StorageException;
}
