package lol.candyriya.continuum.paper.scheduler;

import org.bukkit.plugin.Plugin;

/**
 * Folia flavour of the threaded-regions scheduler.
 *
 * <p>Same API surface as {@link PaperScheduler}, real region semantics: regions
 * tick in parallel, entity tasks land on the region thread owning the entity,
 * global tasks on the global region thread, async tasks off the game loop.
 * All entity access in Continuum goes through {@code PlatformScheduler#entity}
 * or direct {@code EntityScheduler} hops, never through a shared main thread —
 * this class is what keeps the shared code correct on Folia.</p>
 */
public final class FoliaScheduler extends RegionizedScheduler {

    public FoliaScheduler(Plugin plugin) {
        super(plugin);
    }

    @Override
    public String platformName() {
        return "Folia";
    }
}
