package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.api.module.BuiltinModules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanBuilderTest {

    private static PlanBuilder.Node node(String id, int priority, String... dependencies) {
        return new PlanBuilder.Node(id, priority, List.of(dependencies));
    }

    @Test
    void defaultOrderMatchesTheDocumentedBuiltInOrder() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.LOCATION, 100),
                node(BuiltinModules.STATISTICS, 100),
                node(BuiltinModules.RECIPES, 100),
                node(BuiltinModules.ADVANCEMENTS, 100),
                node(BuiltinModules.EXPERIENCE, 100),
                node(BuiltinModules.EFFECTS, 100),
                node(BuiltinModules.VITALS, 100, BuiltinModules.ATTRIBUTES),
                node(BuiltinModules.ENDERCHEST, 100),
                node(BuiltinModules.INVENTORY, 100),
                node(BuiltinModules.GAMEMODE, 100),
                node(BuiltinModules.ATTRIBUTES, 90));

        List<String> order = PlanBuilder.build(modules, List.of()).stream().map(PlanBuilder.Node::id).toList();

        assertEquals(List.of(
                BuiltinModules.ATTRIBUTES,
                BuiltinModules.GAMEMODE,
                BuiltinModules.INVENTORY,
                BuiltinModules.ENDERCHEST,
                BuiltinModules.VITALS,
                BuiltinModules.EFFECTS,
                BuiltinModules.EXPERIENCE,
                BuiltinModules.ADVANCEMENTS,
                BuiltinModules.RECIPES,
                BuiltinModules.STATISTICS,
                BuiltinModules.LOCATION), order);
    }

    @Test
    void dependenciesAreRestoredBeforeDependentsEvenAgainstConfigOrder() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.VITALS, 100, BuiltinModules.ATTRIBUTES),
                node(BuiltinModules.ATTRIBUTES, 90));

        // config asks for vitals first — the dependency still wins
        List<String> order = PlanBuilder.build(modules,
                        List.of(BuiltinModules.VITALS, BuiltinModules.ATTRIBUTES))
                .stream().map(PlanBuilder.Node::id).toList();

        assertEquals(List.of(BuiltinModules.ATTRIBUTES, BuiltinModules.VITALS), order);
    }

    @Test
    void configuredOrderControlsPlacementOfUnrelatedModules() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.ATTRIBUTES, 90),
                node(BuiltinModules.VITALS, 100, BuiltinModules.ATTRIBUTES),
                node(BuiltinModules.RECIPES, 100),
                node(BuiltinModules.GAMEMODE, 100),
                node(BuiltinModules.LOCATION, 100));

        List<String> order = PlanBuilder.build(modules,
                        List.of(BuiltinModules.RECIPES, BuiltinModules.ATTRIBUTES))
                .stream().map(PlanBuilder.Node::id).toList();

        assertEquals(BuiltinModules.RECIPES, order.get(0));
        assertTrue(order.indexOf(BuiltinModules.ATTRIBUTES) < order.indexOf(BuiltinModules.VITALS),
                "vitals must follow its dependency");
        assertEquals(BuiltinModules.LOCATION, order.get(order.size() - 1));
    }

    @Test
    void configuredOrderToleratesUnknownIds() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.ATTRIBUTES, 90),
                node(BuiltinModules.GAMEMODE, 100));

        List<String> order = PlanBuilder.build(modules,
                        List.of("continuum.madeup", BuiltinModules.GAMEMODE))
                .stream().map(PlanBuilder.Node::id).toList();

        assertEquals(BuiltinModules.GAMEMODE, order.get(0));
    }

    @Test
    void cyclesAreRejectedWithTheStuckModulesNamed() {
        List<PlanBuilder.Node> modules = List.of(
                node("continuum.alpha", 100, "continuum.beta"),
                node("continuum.beta", 100, "continuum.alpha"),
                node("continuum.gamma", 100));

        PlanBuilder.PlanException e = assertThrows(PlanBuilder.PlanException.class,
                (Executable) () -> PlanBuilder.build(modules, List.of()));
        assertTrue(e.getMessage().contains("continuum.alpha"), e.getMessage());
        assertTrue(e.getMessage().contains("continuum.beta"), e.getMessage());
    }

    @Test
    void duplicateIdsAreRejected() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.VITALS, 100),
                node(BuiltinModules.VITALS, 50));

        assertThrows(PlanBuilder.PlanException.class,
                (Executable) () -> PlanBuilder.build(modules, List.of()));
    }

    @Test
    void missingDependencyIsNotAnOrderingConstraintButKeepsTheNode() {
        List<PlanBuilder.Node> modules = List.of(
                node(BuiltinModules.VITALS, 100, BuiltinModules.ATTRIBUTES),   // attributes disabled: absent
                node(BuiltinModules.GAMEMODE, 100));

        List<String> order = PlanBuilder.build(modules, List.of()).stream().map(PlanBuilder.Node::id).toList();

        assertEquals(2, order.size());
        assertTrue(order.contains(BuiltinModules.VITALS));
    }

    @Test
    void planIsDeterministicAcrossReorderingsOfTheInput() {
        List<PlanBuilder.Node> forward = List.of(
                node(BuiltinModules.ATTRIBUTES, 90),
                node(BuiltinModules.VITALS, 100, BuiltinModules.ATTRIBUTES),
                node(BuiltinModules.INVENTORY, 100),
                node(BuiltinModules.LOCATION, 100));
        List<PlanBuilder.Node> reversed = forward.reversed();

        List<String> a = PlanBuilder.build(forward, List.of()).stream().map(PlanBuilder.Node::id).toList();
        List<String> b = PlanBuilder.build(reversed, List.of()).stream().map(PlanBuilder.Node::id).toList();

        assertEquals(a, b);
    }
}
