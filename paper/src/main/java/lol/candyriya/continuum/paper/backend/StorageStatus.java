package lol.candyriya.continuum.paper.backend;

/**
 * Health of the snapshot storage layer, as seen by the backend.
 *
 * <p>{@link #UP} — storage answers; logins proceed normally.</p>
 * <p>{@link #DEGRADED} — storage cannot serve snapshots; the join gate refuses
 * logins when {@code gate.disallow-on-storage-degraded} is on (the default),
 * so nobody lands on half-restored state.</p>
 */
public enum StorageStatus {
    UP,
    DEGRADED
}
