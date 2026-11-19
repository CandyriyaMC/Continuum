package lol.candyriya.continuum.paper.modules.attributes;

import lol.candyriya.continuum.api.module.ModuleInfo;
import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.SyncModule;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The restore plan, as documented by the api contract: dependencies first
 * (topological), then ascending priority, ties broken by id. The plan itself lives
 * in the platform, so this test replays the documented rule over our metadata and a
 * vitals lookalike to prove attributes restore before the health module under both
 * declarations (dependency and bare priority).
 */
class RestoreOrderingPlanTest {

    private static ModuleInfo attributes() {
        return new ModuleInfo(AttributesContract.ID, AttributesContract.SCHEMA_VERSION, false,
                AttributesContract.PRIORITY, AttributesContract.DEPENDENCIES, "continuum");
    }

    private static List<String> plan(List<ModuleInfo> modules) {
        // the documented rule, spelled out: dependencies first (stable topo), then
        // priority ascending, ties by id
        Map<String, ModuleInfo> byId = modules.stream()
                .collect(java.util.stream.Collectors.toMap(ModuleInfo::id, module -> module));
        var done = new java.util.HashSet<String>();
        var ordered = new ArrayList<String>();
        var remaining = new LinkedHashSet<>(byId.keySet());
        while (!remaining.isEmpty()) {
            var eligible = remaining.stream()
                    .filter(id -> byId.get(id).dependencies().stream().allMatch(done::contains))
                    .min(Comparator.comparingInt((String id) -> byId.get(id).priority()).thenComparing(id -> id))
                    .orElseThrow(() -> new IllegalStateException("dependency cycle among " + remaining));
            remaining.remove(eligible);
            ordered.add(eligible);
            done.add(eligible);
        }
        return ordered;
    }

    @Test
    void attributesRestoreBeforeVitalsWhenVitalsDeclaresTheDependency() {
        var vitalsInfo = new ModuleInfo(BuiltinModules.VITALS, 1, true,
                SyncModule.DEFAULT_PRIORITY,
                List.of(BuiltinModules.ATTRIBUTES), // vitals must raise max health before writing health
                "continuum");

        // order of the input must not matter — only priorities and dependencies do
        assertEquals(List.of("continuum.attributes", "continuum.vitals"),
                plan(List.of(vitalsInfo, attributes())));
        assertEquals(List.of("continuum.attributes", "continuum.vitals"),
                plan(List.of(attributes(), vitalsInfo)));
    }

    @Test
    void attributesRestoreBeforeVitalsByPriorityEvenWithoutTheDependency() {
        var vitalsInfo = new ModuleInfo(BuiltinModules.VITALS, 1, true,
                SyncModule.DEFAULT_PRIORITY, List.of(), "continuum");

        assertEquals(List.of("continuum.attributes", "continuum.vitals"),
                plan(List.of(vitalsInfo, attributes())));
    }

    @Test
    void attributesBeatsVitalsOnTheIdTieBreakAtEqualPriority() {
        // sanity check of the tie-break the planner relies on: same priority → alphabetical,
        // and continuum.attributes sorts before continuum.vitals
        var attributesInfo = new ModuleInfo(AttributesContract.ID, AttributesContract.SCHEMA_VERSION, false,
                SyncModule.DEFAULT_PRIORITY, List.of(), "continuum");
        var vitalsInfo = new ModuleInfo(BuiltinModules.VITALS, 1, true,
                SyncModule.DEFAULT_PRIORITY, List.of(), "continuum");

        assertEquals(List.of("continuum.attributes", "continuum.vitals"),
                plan(List.of(vitalsInfo, attributesInfo)));
    }
}
