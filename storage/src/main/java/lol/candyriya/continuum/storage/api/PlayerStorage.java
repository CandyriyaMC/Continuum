package lol.candyriya.continuum.storage.api;

import lol.candyriya.continuum.common.error.StorageException;
import lol.candyriya.continuum.common.session.SessionState;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single source of truth for player state: snapshots, sessions, fencing
 * tokens, the outbox and statistics.
 *
 * <p>Fencing discipline: every guarded write checks the token inside the
 * atomic database operation, never in application memory. A snapshot is only
 * stored when its token is newer than the stored one; stale writes are
 * rejected with {@link SaveResult.StaleToken}, never by exception.</p>
 *
 * <p>Outbox discipline: {@code save} with outbox events appends them in the
 * same transaction as the snapshot row. Either both land or neither does.</p>
 *
 * <p>History discipline: every successful save also records a row in the
 * snapshot history, keeping at most {@code historyKeepLast} rows per player.
 * Rollback is implemented by reading an older row and saving it again under a
 * new token — the current row is never deleted.</p>
 *
 * <p>Implementations must be safe to call from multiple threads; the pool
 * inside an implementation is the caller's only shared resource.</p>
 */
public interface PlayerStorage extends AutoCloseable {

    /**
     * Loads the current snapshot for {@code playerId}.
     *
     * @return the snapshot, or empty when the player has never been saved
     * @throws StorageException when the database cannot complete the read
     */
    Optional<PlayerSnapshot> load(UUID playerId) throws StorageException;

    /**
     * Guarded save with default options (reason {@code API}, backend
     * {@code unknown}, no outbox events). See
     * {@link #save(PlayerSnapshot, long, SaveOptions)}.
     */
    default SaveResult save(PlayerSnapshot snapshot, long fencingToken) throws StorageException {
        return save(snapshot, fencingToken, SaveOptions.defaults());
    }

    /**
     * Stores {@code snapshot} if {@code fencingToken} is newer than the stored
     * token (or no row exists yet). The check and the write are one atomic
     * database operation.
     *
     * <p>On success a history row is recorded (and old ones pruned beyond the
     * configured keep), any outbox events ride the same transaction, and the
     * player row's modification time is bumped.</p>
     *
     * @param snapshot     the snapshot to store; its player id selects the row
     * @param fencingToken the caller's fencing token, from
     *                     {@link #acquireSession(UUID, String)} or
     *                     {@link #getSession(UUID)}; never negative
     * @param options      reason, backend and outbox events for this save
     * @return {@link SaveResult.Saved} with the history id, or
     *         {@link SaveResult.StaleToken} when a newer session already owns the row;
     *         nothing is modified on the stale path
     * @throws StorageException when the database cannot complete the write
     */
    SaveResult save(PlayerSnapshot snapshot, long fencingToken, SaveOptions options) throws StorageException;

    /**
     * Creates a session for {@code playerId} on {@code serverId}, issuing a
     * fresh, globally monotonically increasing fencing token. Any previously
     * open sessions for the player are marked closed in the same transaction —
     * "acquire" is the ownership claim.
     *
     * @param playerId the player, never null
     * @param serverId the backend taking ownership, non-blank, at most 64 characters
     * @return the fresh session; its token is strictly greater than any token issued before
     * @throws StorageException when the database cannot complete the operation
     */
    SessionRecord acquireSession(UUID playerId, String serverId) throws StorageException;

    /**
     * Explicit lifecycle transition, guarded by the fencing token. A session
     * in {@link SessionState#RELEASED} is terminal: transitions out of it are
     * refused. Refinement of the workflow (CONNECTING → LOADING → ACTIVE → ...)
     * is the caller's business; storage guarantees atomicity and fencing.
     *
     * @return the updated session, or empty when no non-released session exists
     *         for this player with this token
     * @throws StorageException when the database cannot complete the operation
     */
    Optional<SessionRecord> transitionSession(UUID playerId, long fencingToken, SessionState state) throws StorageException;

    /**
     * Renews the session lease and bumps the heartbeat timestamp, guarded by
     * the fencing token. A stale heartbeat never extends a lost lease.
     *
     * @return the updated session, or empty when no owned session exists with this token
     * @throws StorageException when the database cannot complete the operation
     */
    Optional<SessionRecord> heartbeatSession(UUID playerId, long fencingToken) throws StorageException;

    /**
     * Releases the session guarded by the fencing token. Idempotent for a
     * clean release; a stale token (or an already-released session) yields
     * {@code false} and changes nothing.
     *
     * @return true when this call closed the session
     * @throws StorageException when the database cannot complete the operation
     */
    boolean releaseSession(UUID playerId, long fencingToken) throws StorageException;

    /**
     * The newest session row for {@code playerId}, regardless of state
     * (released sessions included). Ordered by fencing token, descending.
     *
     * @return the latest session, or empty when the player never had one
     * @throws StorageException when the database cannot complete the read
     */
    Optional<SessionRecord> getSession(UUID playerId) throws StorageException;

    /**
     * The most recent history rows for {@code playerId}, newest first.
     *
     * @param limit maximum rows to return, 1..1000
     * @return summaries without payloads, never null
     * @throws StorageException when the database cannot complete the read
     */
    List<SnapshotSummary> listHistory(UUID playerId, int limit) throws StorageException;

    /**
     * Rollback-read: materializes one history row by id. The returned record
     * carries the historical fencing token — it is not valid for a guarded
     * write; a rollback is performed by re-saving the envelope under a fresh
     * token from a new session.
     *
     * @return the snapshot, or empty when no history row has this id
     * @throws StorageException when the database cannot complete the read
     */
    Optional<PlayerSnapshot> loadSnapshot(long snapshotId) throws StorageException;

    /** Current exact statistics for {@code playerId}; empty when never recorded. */
    PlayerStats readStats(UUID playerId) throws StorageException;

    /**
     * Replaces the whole stored statistics set for {@code playerId} inside one
     * transaction (delete + insert). Not a merge: keys absent from
     * {@code stats} are erased. Intended for baseline seeding and recovery.
     *
     * @throws StorageException when the database cannot complete the write
     */
    void writeStatsBaseline(UUID playerId, Map<String, Long> stats) throws StorageException;

    /** Accumulate-mutations ({@code stat = stat + ?}), atomic, no fencing required. */
    StatisticsDeltaApplier statistics();

    /**
     * Appends one outbox event in its own transaction. Events tied to a
     * snapshot save should ride {@link #save(PlayerSnapshot, long, SaveOptions)}
     * instead, so the snapshot and its events commit or roll back together.
     *
     * @return the stored entry, with its id
     * @throws StorageException when the database cannot complete the write
     */
    OutboxEntry appendOutbox(String msgType, UUID playerId, String payloadJson) throws StorageException;

    /**
     * Peeks at unpublished events, oldest first, without locking. Useful for
     * monitoring and tests; the publish path is {@link #publishOutbox}.
     *
     * @param limit maximum rows to return, 1..1000
     * @throws StorageException when the database cannot complete the read
     */
    List<OutboxEntry> pollOutbox(int limit) throws StorageException;

    /**
     * Claims up to {@code limit} unpublished entries inside one transaction
     * ({@code FOR UPDATE SKIP LOCKED}), hands each to {@code publisher}, and
     * records the outcome in the same transaction: confirmed entries are
     * marked published, failures get {@code attempts + 1} and the error text.
     * Entries whose {@code attempts} already reached {@code maxAttempts} are
     * not claimed.
     *
     * <p>Note on delivery semantics: with a crash after broker confirm but
     * before the transaction commits, the event is republished — consumers
     * must be idempotent ({@code id} is the natural idempotency key).</p>
     *
     * @param limit      maximum entries to claim, 1..1000
     * @param maxAttempts skip entries at or above this delivery attempt count, {@code >= 1}
     * @return how many entries were marked published in this run
     * @throws StorageException when the database cannot complete the operation
     */
    int publishOutbox(int limit, int maxAttempts, OutboxPublisher publisher) throws StorageException;

    /**
     * Marks entries as published. Ids that are already published are ignored.
     *
     * @param ids the entries to mark, may be empty
     * @throws StorageException when the database cannot complete the write
     */
    void markOutboxPublished(Collection<Long> ids) throws StorageException;

    /**
     * Marks one entry as failed: {@code attempts + 1}, {@code last_error} set
     * (truncated to 512 characters). Published entries are not affected.
     *
     * @throws StorageException when the database cannot complete the write
     */
    void markOutboxFailed(long id, String error) throws StorageException;

    /** Closes the underlying pool. After close, every operation fails fast. */
    @Override
    void close();
}
