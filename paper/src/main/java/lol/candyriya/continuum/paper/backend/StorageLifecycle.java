package lol.candyriya.continuum.paper.backend;

/**
 * Lifecycle of the storage layer as seen by the backend.
 *
 * <p>Implemented by the storage integration; the backend composition only ever
 * sees this interface. {@link #status()} backs the join gate's
 * degraded-storage refusal; {@link #close()} is called on shutdown and must
 * release pools and connections deterministically.</p>
 */
public interface StorageLifecycle extends AutoCloseable {

    /** Current storage status. Must not block; read cached health, never do I/O inline. */
    StorageStatus status();

    /** Stops the storage layer. Idempotent. */
    @Override
    void close();
}
