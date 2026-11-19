package lol.candyriya.continuum.paper.scheduler;

import org.bukkit.plugin.Plugin;

/**
 * Paper flavour of the threaded-regions scheduler.
 *
 * <p>Identical API surface to {@link FoliaScheduler} — Paper ships the same
 * global/entity/async scheduler types — but with Paper's semantics: one main
 * region owns every loaded chunk, so "entity thread", "global region thread"
 * and the game tick are effectively the same thread. Tasks and invariants are
 * written to be correct on either platform, so the implementation is shared;
 * this class exists so the detected platform is explicit in the service graph
 * and logs.</p>
 */
public final class PaperScheduler extends RegionizedScheduler {

    public PaperScheduler(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String platformName() {
        return "Paper";
    }
}
