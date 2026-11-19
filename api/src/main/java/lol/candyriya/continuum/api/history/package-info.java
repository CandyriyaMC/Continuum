/**
 * Snapshot history and rollback — the non-destructive undo.
 *
 * <p>Continuum keeps a bounded history of snapshots per player (configured per backend).
 * This package exposes listing and the rollback operation: a rollback promotes a
 * historical snapshot to the basis of the next restore by writing a faithful copy under a
 * new id, never by overwriting or deleting anything.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.history;

import org.jetbrains.annotations.ApiStatus;
