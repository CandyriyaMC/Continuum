package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.SyncModule;

import org.bukkit.entity.Player;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Property-ish coverage for restore planning: the plan must be a valid
 * topological linearization of the module graph no matter in which order the
 * modules were registered. Registration order is nobody's source of truth;
 * dependencies are.
 *
 * <p>Exhaustive: all 720 permutations of a 6-module graph with an asymmetric
 * dependency shape (two independent roots, two shared middle tiers, one tail),
 * exercised through {@link ModuleRuntime#of} — the same assembly path the
 * production registry uses.</p>
 */
class ModuleRuntimePermutationTest {

    private static final List<String> IDS = List.of(
            "continuum.test.alpha",
            "continuum.test.bravo",
            "continuum.test.charlie",
            "continuum.test.delta",
            "continuum.test.echo",
            "continuum.test.foxtrot");

    // alpha <- bravo, alpha <- charlie, {bravo, charlie} <- delta <- echo, foxtrot independent
    private static final Map<String, List<String>> DEPS = Map.of(
            "continuum.test.alpha", List.of(),
            "continuum.test.bravo", List.of("continuum.test.alpha"),
            "continuum.test.charlie", List.of("continuum.test.alpha"),
            "continuum.test.delta", List.of("continuum.test.bravo", "continuum.test.charlie"),
            "continuum.test.echo", List.of("continuum.test.delta"),
            "continuum.test.foxtrot", List.of());

    private static SyncModule<Player, String> stub(String id) {
        return new SyncModule<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public CompletionStage<String> capture(Player player) {
                return CompletableFuture.completedFuture("payload");
            }

            @Override
            public CompletionStage<Void> restore(Player player, String data) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public int priority() {
                return SyncModule.DEFAULT_PRIORITY;
            }

            @Override
            public List<String> dependencies() {
                return DEPS.get(id);
            }
        };
    }

    private static ModuleRuntime.RegisteredModule module(String id) {
        return new ModuleRuntime.RegisteredModule(id, stub(id), String.class, FailurePolicy.SKIP, true);
    }

    private static void assertValidLinearization(List<ModuleRuntime.RegisteredModule> plan) {
        List<String> order = plan.stream().map(ModuleRuntime.RegisteredModule::id).toList();
        assertEquals(IDS.size(), new java.util.HashSet<>(order).size(), "plan must contain every module exactly once");
        for (int i = 0; i < order.size(); i++) {
            for (String dependency : DEPS.get(order.get(i))) {
                int depIndex = order.indexOf(dependency);
                assertTrue(depIndex >= 0, "dependency " + dependency + " missing from the plan");
                assertTrue(depIndex < i,
                        "module " + order.get(i) + " was planned before its dependency " + dependency);
            }
        }
    }

    @Test
    void everyRegistrationOrderPermutationYieldsAValidLinearization() {
        List<String> expected = null;
        int[] permutations = {0};
        for (List<String> permutation : permutations(IDS)) {
            List<ModuleRuntime.RegisteredModule> modules = permutation.stream()
                    .map(ModuleRuntimePermutationTest::module)
                    .collect(Collectors.toList());
            ModuleRuntime runtime = ModuleRuntime.of(modules, List.of());
            List<ModuleRuntime.RegisteredModule> plan = runtime.planOrder();

            assertValidLinearization(plan);

            List<String> ids = plan.stream().map(ModuleRuntime.RegisteredModule::id).toList();
            if (expected == null) {
                expected = ids;
            } else {
                assertEquals(expected, ids,
                        "plan changed with registration order; ordering must not depend on who signed up first");
            }
            permutations[0]++;
        }
        assertEquals(720, permutations[0], "6! = 720 permutations must all have been exercised");
    }

    @Test
    void planPrefersDependencyOrderOverRegistrationOrderEvenForTheDeepChain() {
        // the reverse of the natural acquisition order: deepest tail first
        List<String> reversedRegistration = IDS.reversed();
        List<ModuleRuntime.RegisteredModule> modules = reversedRegistration.stream()
                .map(ModuleRuntimePermutationTest::module)
                .collect(Collectors.toList());

        ModuleRuntime runtime = ModuleRuntime.of(modules, List.of());
        List<String> order = runtime.planOrder().stream().map(ModuleRuntime.RegisteredModule::id).toList();

        assertTrue(order.indexOf("continuum.test.alpha") < order.indexOf("continuum.test.bravo"));
        assertTrue(order.indexOf("continuum.test.alpha") < order.indexOf("continuum.test.charlie"));
        assertTrue(order.indexOf("continuum.test.bravo") < order.indexOf("continuum.test.delta"));
        assertTrue(order.indexOf("continuum.test.charlie") < order.indexOf("continuum.test.delta"));
        assertTrue(order.indexOf("continuum.test.delta") < order.indexOf("continuum.test.echo"));
    }

    /** All permutations of {@code items} (Heap's algorithm would also work; recursion is fine at n=6). */
    private static List<List<String>> permutations(List<String> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String head = items.get(i);
            List<String> rest = new ArrayList<>(items);
            rest.remove(i);
            for (List<String> tail : permutations(rest)) {
                List<String> full = new ArrayList<>(tail.size() + 1);
                full.add(head);
                full.addAll(tail);
                out.add(full);
            }
        }
        return out;
    }
}
