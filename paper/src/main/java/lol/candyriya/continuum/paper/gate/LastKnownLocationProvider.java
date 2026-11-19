package lol.candyriya.continuum.paper.gate;

import org.bukkit.Location;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the last location stored for a player, for the {@code LAST_KNOWN_LOCATION}
 * spawn mode.
 *
 * <p>This build ships no implementation — the location data lives in the
 * synchronization layer, which is wired through a later integration. Until then
 * the default provider returns {@link Optional#empty()} and the spawn policy
 * falls back to the server spawn.</p>
 *
 * <p>Implementations must be cheap (an in-memory map lookup, not a database
 * round-trip) or run their own I/O off the calling thread and return whatever
 * is cached — they are called on the async spawn-decision thread and must not
 * block it for long.</p>
 */
@FunctionalInterface
public interface LastKnownLocationProvider {

    /** The last-known location for {@code playerId}, or empty when none is available. */
    Optional<Location> lastKnownLocation(UUID playerId);
}
