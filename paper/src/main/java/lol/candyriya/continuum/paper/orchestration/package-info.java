/**
 * The backend sync pipeline: module registration, snapshot capture, join-gate
 * restore, per-player FIFO serialization, dirty tracking and the shared
 * last-known-good baseline.
 *
 * <p>Entry points: {@code OrchestrationWiring} (composition), {@code SnapshotManager}
 * (capture), {@code RestoreOrchestrator} (restore), {@code PlayerFifoQueue} (serialization),
 * {@code DirtyTracker} (autosave optimization). Implementation details of the
 * sync protocol live in the storage and api modules; this package orchestrates
 * them, it does not reimplement them.</p>
 */
package lol.candyriya.continuum.paper.orchestration;
