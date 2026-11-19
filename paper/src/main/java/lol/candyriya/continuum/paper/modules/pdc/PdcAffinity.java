package lol.candyriya.continuum.paper.modules.pdc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Entity-thread affinity guard for the lifecycle methods.
 *
 * <p>Everything here has to run on the entity's owning thread/region - the main
 * thread on Paper, the region thread of the player on Folia. The {@code SyncModule}
 * contract already guarantees it; this is the tripwire that turns a platform
 * regression into an immediate, legible failure instead of a heisenbug inside a
 * serializer three frames later.</p>
 *
 * <p>Lives in its own class so the module class itself never names a platform
 * type a plain-JUnit runner cannot load; unit tests link the module without a
 * platform jar and never reach this code.</p>
 */
final class PdcAffinity {

    private PdcAffinity() {
        // static access only
    }

    /**
     * Throws {@link IllegalStateException} when the current thread does not own {@code player}.
     */
    static void requirePlayerThread(Player player, String operation) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            throw new IllegalStateException(operation + " must run on the owning thread/region of the player,"
                    + " but is running on '" + Thread.currentThread().getName() + "';"
                    + " Continuum schedules module lifecycle calls on the player's thread - something upstream broke that contract");
        }
    }
}
