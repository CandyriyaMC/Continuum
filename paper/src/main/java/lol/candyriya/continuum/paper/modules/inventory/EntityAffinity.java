package lol.candyriya.continuum.paper.modules.inventory;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/**
 * Entity-thread affinity guard for the lifecycle methods.
 *
 * <p>Everything in this package that reads or mutates live player state has to run on the
 * entity's owning thread/region — the main thread on Paper, the region thread of the player
 * on Folia. The {@code SyncModule} contract already guarantees it; this is the tripwire that
 * turns a platform regression into an immediate, legible failure instead of a heisenbug inside
 * a serializer three frames later.</p>
 */
final class EntityAffinity {

    private EntityAffinity() {
        // static access only
    }

    /**
     * Throws {@link IllegalStateException} when the current thread does not own {@code entity}.
     * This is a defense-in-depth check, not a scheduling primitive — Continuum calls the
     * lifecycle methods on the right thread by contract.
     */
    static void requireEntityThread(Entity entity, String operation) {
        if (!Bukkit.isOwnedByCurrentRegion(entity)) {
            throw new IllegalStateException(operation + " must run on the owning thread/region of the entity,"
                    + " but is running on '" + Thread.currentThread().getName() + "';"
                    + " Continuum schedules module lifecycle calls on the player's thread — something upstream broke that contract");
        }
    }
}
