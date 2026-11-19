package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.ModuleIds;

import java.util.List;
import java.util.Objects;

/**
 * Metadata of one first-party (Continuum) module, as seen by the custom adapter registry.
 * The registry uses it (a) as context for dependency cycle detection across the whole
 * module graph, and (b) to know which {@code continuum.*} dependencies may legitimately be
 * declared by adapters — an adapter may depend on a first-party module, and whether that
 * dependency exists at restore time is knowable only from what actually registered.
 *
 * <p>First-party modules never depend on third-party adapters, so foreign edges can only
 * make the graph more connected, never contradict the adapter-only cycle check.</p>
 *
 * @param id           the first-party module id, never null, well-formed (expected to be reserved {@code continuum.*})
 * @param priority     restore planning hint (as declared by the module itself)
 * @param dependencies ids this module declares as dependencies (as reported by {@code SyncModule#dependencies()})
 * @since 1.0.0
 */
public record FirstPartyModule(String id, int priority, List<String> dependencies) {

    public FirstPartyModule {
        Objects.requireNonNull(id, "id must not be null");
        ModuleIds.requireValid(id);
        Objects.requireNonNull(dependencies, "dependencies must not be null");
        dependencies = List.copyOf(dependencies);
        for (String dependency : dependencies) {
            if (dependency == null) {
                throw new IllegalArgumentException("dependencies must not contain null elements");
            }
            ModuleIds.requireValid(dependency);
        }
    }
}
