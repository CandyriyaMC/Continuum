/**
 * The snapshot model — what Continuum persists, why, and how a capture ended.
 *
 * <p>Snapshots are the unit of durable player state in Continuum: full-fidelity,
 * self-contained, fencing-token-protected copies of everything Continuum synchronizes.
 * This package holds the metadata type ({@link lol.candyriya.continuum.api.snapshot.PlayerSnapshot}),
 * the reason/result vocabularies, and the read/trigger surface
 * ({@link lol.candyriya.continuum.api.snapshot.SnapshotService}).</p>
 *
 * <p>History and rollback of stored snapshots live in
 * {@code lol.candyriya.continuum.api.history}; capture/restore accounting lives in
 * {@code lol.candyriya.continuum.api.report}.</p>
 *
 * @since 1.0.0
 */
@ApiStatus.AvailableSince("1.0.0")
package lol.candyriya.continuum.api.snapshot;

import org.jetbrains.annotations.ApiStatus;
