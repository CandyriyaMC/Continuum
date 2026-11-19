package lol.candyriya.continuum.paper.orchestration;

/**
 * The minimal health signal the pipeline needs from the storage layer: is the
 * database believed to serve snapshots right now?
 *
 * <p>Split out from {@link BackendStorage} so the capture pipeline can be
 * exercised with a fake in tests; production passes the real storage.</p>
 */
public interface StorageHealth {

    /** True while the database is believed to be up. */
    boolean isUp();
}
