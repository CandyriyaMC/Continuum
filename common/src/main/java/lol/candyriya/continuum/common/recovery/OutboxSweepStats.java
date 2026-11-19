package lol.candyriya.continuum.common.recovery;

/**
 * Point-in-time counters from an {@link OutboxSweeper}. Snapshot them; do not
 * keep the instance for accounting.
 *
 * @param sweeps        completed sweeps since start (successful or not)
 * @param published     entries confirmed and marked published across all sweeps
 * @param setAside      distinct entries observed at their attempt budget
 * @param claimFailures sweeps that blew up before publishing (claim/SQL/IO)
 */
public record OutboxSweepStats(long sweeps, long published, long setAside, long claimFailures) {

    public static OutboxSweepStats empty() {
        return new OutboxSweepStats(0, 0, 0, 0);
    }
}
