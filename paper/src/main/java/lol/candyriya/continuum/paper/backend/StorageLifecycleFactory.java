package lol.candyriya.continuum.paper.backend;

import lol.candyriya.continuum.common.config.ContinuumConfig;

/**
 * Factory for the storage integration. Called first during composition — the
 * join gate's degraded-storage refusal depends on {@link StorageLifecycle#status()}.
 */
@FunctionalInterface
public interface StorageLifecycleFactory {

    /** Start the storage layer; the returned lifecycle is probed by the gate and closed on shutdown. */
    StorageLifecycle start(ContinuumConfig config);
}
