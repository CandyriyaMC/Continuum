package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.DuplicateModuleIdException;
import lol.candyriya.continuum.api.module.InvalidModuleDefinitionException;
import lol.candyriya.continuum.api.module.InvalidModuleIdException;
import lol.candyriya.continuum.api.module.ModuleInfo;
import lol.candyriya.continuum.api.module.ModuleRegistration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterRegistryTest {

    private static StubAdapter adapter(String id) {
        return new StubAdapter(id);
    }

    // ---------- basic registration ----------

    @Test
    void registersAndReportsMetadata() {
        var registry = new AdapterRegistry();
        ModuleRegistration handle = registry.register(adapter("test.alpha"), "test-source");
        assertEquals("test.alpha", handle.id());
        assertTrue(handle.isActive());
        assertTrue(registry.isRegistered("test.alpha"));
        assertEquals(1, registry.registeredCount());
        ModuleInfo info = registry.module("test.alpha").orElseThrow();
        assertEquals("test.alpha", info.id());
        assertEquals(1, info.version());
        assertEquals("test-source", info.source());
    }

    @Test
    void duplicateIdRejectedAndOriginalKeepsItsPlace() {
        var registry = new AdapterRegistry();
        ModuleRegistration first = registry.register(adapter("test.alpha"), "first-plugin");
        assertThrows(DuplicateModuleIdException.class,
                () -> registry.register(adapter("test.alpha"), "second-plugin"));
        assertEquals(1, registry.registeredCount());
        assertEquals("first-plugin", registry.module("test.alpha").orElseThrow().source());
        assertTrue(first.isActive());
    }

    @Test
    void reservedFirstPartyNamespaceRejected() {
        var registry = new AdapterRegistry();
        assertThrows(InvalidModuleIdException.class,
                () -> registry.register(adapter(BuiltinModules.INVENTORY), "test-source"));
        assertThrows(InvalidModuleIdException.class,
                () -> registry.register(adapter("continuum.anything"), "test-source"));
    }

    @Test
    void malformedIdsRejected() {
        var registry = new AdapterRegistry();
        for (String broken : List.of("nodashes", "Trailing.", ".Leading", "Upper.Case", "double..dot", "")) {
            assertThrows(InvalidModuleIdException.class,
                    () -> registry.register(adapter(broken), "test-source"), broken);
        }
    }

    @Test
    void schemaVersionBelowOneRejected() {
        var registry = new AdapterRegistry();
        StubAdapter broken = adapter("test.alpha");
        broken.version = 0;
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(broken, "test-source"));
    }

    @Test
    void selfDependencyRejected() {
        var registry = new AdapterRegistry();
        StubAdapter broken = adapter("test.alpha");
        broken.dependencies = List.of("test.alpha");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(broken, "test-source"));
    }

    @Test
    void duplicateDependencyRejected() {
        var registry = new AdapterRegistry();
        StubAdapter broken = adapter("test.alpha");
        broken.dependencies = List.of("test.beta", "test.beta");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(broken, "test-source"));
    }

    @Test
    void nonPositiveTimeoutRejected() {
        var registry = new AdapterRegistry();
        StubAdapter broken = adapter("test.alpha");
        broken.timeout = java.time.Duration.ZERO;
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(broken, "test-source"));
    }

    @Test
    void unknownDependencyAllowedAtRegistration() {
        // a dependency on a module that may never appear is legal; restore reports DEPENDENCY_MISSING
        var registry = new AdapterRegistry();
        StubAdapter dependent = adapter("test.alpha");
        dependent.dependencies = List.of("nobody.missing");
        assertDoesNotThrow(() -> registry.register(dependent, "test-source"));
    }

    @Test
    void dependencyOnFirstPartyModuleAccepted() {
        var registry = new AdapterRegistry(
                List.of(new FirstPartyModule(BuiltinModules.ATTRIBUTES, 90, List.of()),
                        new FirstPartyModule(BuiltinModules.VITALS, 100, List.of(BuiltinModules.ATTRIBUTES))));
        StubAdapter dependent = adapter("test.alpha");
        dependent.dependencies = List.of(BuiltinModules.VITALS);
        assertDoesNotThrow(() -> registry.register(dependent, "test-source"));
    }

    @Test
    void nullSourceRejected() {
        var registry = new AdapterRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("test.alpha"), null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("test.alpha"), "   "));
    }

    // ---------- cycle detection ----------

    @Test
    void directCycleRejected() {
        var registry = new AdapterRegistry();
        StubAdapter first = adapter("test.alpha");
        first.dependencies = List.of("test.beta");
        registry.register(first, "test-source");
        StubAdapter second = adapter("test.beta");
        second.dependencies = List.of("test.alpha");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(second, "test-source"));
        // the offending adapter never landed
        assertFalse(registry.isRegistered("test.beta"));
    }

    @Test
    void transitiveCycleRejected() {
        var registry = new AdapterRegistry();
        StubAdapter a = adapter("test.a");
        a.dependencies = List.of("test.b");
        StubAdapter b = adapter("test.b");
        b.dependencies = List.of("test.c");
        StubAdapter c = adapter("test.c");
        c.dependencies = List.of("test.a");
        registry.register(a, "test-source");
        registry.register(b, "test-source");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(c, "test-source"));
    }

    @Test
    void cycleClosedByLateDanglingDependencyRejected() {
        // test.a declares a dependency on a module that does not exist yet — legal at the time
        var registry = new AdapterRegistry();
        StubAdapter a = adapter("test.a");
        a.dependencies = List.of("test.delayed");
        registry.register(a, "test-source");
        // when the missing piece appears, closing the loop must be rejected
        StubAdapter delayed = adapter("test.delayed");
        delayed.dependencies = List.of("test.a");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(delayed, "test-source"));
    }

    @Test
    void cycleThroughFirstPartyModuleRejected() {
        // a first-party module whose (composition-provided) dependencies point at an adapter
        // makes an adapter→first-party edge cyclic; registration must notice
        var registry = new AdapterRegistry(
                List.of(new FirstPartyModule("continuum.externaldecl", 10, List.of("test.alpha"))));
        StubAdapter alpha = adapter("test.alpha");
        alpha.dependencies = List.of("continuum.externaldecl");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(alpha, "test-source"));
    }

    @Test
    void selfCycleThroughFirstPartyChainRejected() {
        var registry = new AdapterRegistry(
                List.of(new FirstPartyModule("continuum.top", 10, List.of("continuum.mid")),
                        new FirstPartyModule("continuum.mid", 20, List.of("continuum.bottom")),
                        new FirstPartyModule("continuum.bottom", 30, List.of("test.alpha"))));
        StubAdapter alpha = adapter("test.alpha");
        alpha.dependencies = List.of("continuum.top");
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(alpha, "test-source"));
    }

    @Test
    void firstPartyDependencyDoesNotRejectNormalRegistration() {
        var registry = new AdapterRegistry(
                List.of(new FirstPartyModule(BuiltinModules.VITALS, 100, List.of(BuiltinModules.ATTRIBUTES)),
                        new FirstPartyModule(BuiltinModules.ATTRIBUTES, 90, List.of())));
        StubAdapter alpha = adapter("test.alpha");
        alpha.dependencies = List.of(BuiltinModules.VITALS);
        assertDoesNotThrow(() -> registry.register(alpha, "test-source"));
    }

    // ---------- unregistration ----------

    @Test
    void unregisterRemovesFromPlanningAndModule() {
        var registry = new AdapterRegistry();
        ModuleRegistration handle = registry.register(adapter("test.alpha"), "test-source");
        handle.unregister();
        assertFalse(handle.isActive());
        assertFalse(registry.isRegistered("test.alpha"));
        assertEquals(0, registry.registeredCount());
        assertTrue(registry.modules().isEmpty());
    }

    @Test
    void unregisterIsIdempotent() {
        var registry = new AdapterRegistry();
        ModuleRegistration handle = registry.register(adapter("test.alpha"), "test-source");
        handle.unregister();
        assertDoesNotThrow(handle::unregister);
        assertFalse(handle.isActive());
    }

    @Test
    void reRegisterAfterUnregisterSucceeds() {
        var registry = new AdapterRegistry();
        registry.register(adapter("test.alpha"), "test-source").unregister();
        assertDoesNotThrow(() -> registry.register(adapter("test.alpha"), "other-source"));
        assertEquals("other-source", registry.module("test.alpha").orElseThrow().source());
    }

    @Test
    void staleHandleDoesNotRemoveNewRegistration() {
        var registry = new AdapterRegistry();
        ModuleRegistration stale = registry.register(adapter("test.alpha"), "first-source");
        stale.unregister();
        ModuleRegistration fresh = registry.register(adapter("test.alpha"), "second-source");
        // closing the stale handle again must not take the fresh registration down
        stale.close();
        assertTrue(fresh.isActive());
        assertTrue(registry.isRegistered("test.alpha"));
    }

    @Test
    void inFlightSnapshotIsUnaffectedByUnregister() {
        var registry = new AdapterRegistry();
        ModuleRegistration handle = registry.register(adapter("test.alpha"), "test-source");
        List<ModuleInfo> snapshot = registry.modules();
        handle.unregister();
        assertEquals(1, snapshot.size());
        assertEquals(0, registry.modules().size());
    }

    // ---------- planning order ----------

    @Test
    void planOrdersByDependenciesThenPriorityThenId() {
        var registry = new AdapterRegistry();
        StubAdapter zeta = adapter("test.zeta");
        zeta.priority = 10;
        StubAdapter alpha = adapter("test.alpha");
        alpha.priority = 20;
        alpha.dependencies = List.of("test.zeta");
        StubAdapter beta = adapter("test.beta");
        beta.priority = 10;
        registry.register(beta, "test-source");
        registry.register(alpha, "test-source");
        registry.register(zeta, "test-source");
        assertEquals(List.of("test.beta", "test.zeta", "test.alpha"),
                registry.modules().stream().map(ModuleInfo::id).toList());
    }

    @Test
    void priorityTiesBreakById() {
        var registry = new AdapterRegistry();
        registry.register(adapter("test.b").priority(50), "s");
        registry.register(adapter("test.a").priority(50), "s");
        registry.register(adapter("test.c").priority(50), "s");
        assertEquals(List.of("test.a", "test.b", "test.c"),
                registry.modules().stream().map(ModuleInfo::id).toList());
    }

    @Test
    void plannedAdaptersAreImmutableAndOrdered() {
        var registry = new AdapterRegistry();
        registry.register(adapter("test.alpha"), "test-source");
        assertThrows(UnsupportedOperationException.class,
                () -> registry.modules().add(ModuleInfo.of(adapter("test.beta"), "s")));
    }

    // ---------- payload type resolution ----------

    @Test
    void payloadTypeResolvedFromGenericHierarchy() {
        // a layer of indirection: fixture level type resolves through an intermediate base class
        var registry = new AdapterRegistry();
        registry.register(new IndirectAdapter("test.indirect"), "test-source");
        assertTrue(registry.isRegistered("test.indirect"));
    }

    @Test
    void rawAdapterWithoutPayloadTypeRejected() {
        var registry = new AdapterRegistry();
        assertThrows(InvalidModuleDefinitionException.class,
                () -> registry.register(new RawAdapter(), "test-source"));
    }

    private static abstract class BaseAdapter<T> implements lol.candyriya.continuum.api.module.SyncAdapter<org.bukkit.entity.Player, T> {
    }

    static final class IndirectAdapter extends BaseAdapter<StubAdapter.Payload> {

        private final String id;

        IndirectAdapter(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public java.util.concurrent.CompletionStage<StubAdapter.Payload> capture(org.bukkit.entity.Player player) {
            return java.util.concurrent.CompletableFuture.completedFuture(new StubAdapter.Payload("x", 1));
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> restore(org.bukkit.entity.Player player,
                                                                  StubAdapter.Payload data) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    /** Raw adapter — payload type cannot be recovered, registration must refuse. */
    @SuppressWarnings("rawtypes")
    static final class RawAdapter implements lol.candyriya.continuum.api.module.SyncAdapter {

        @Override
        public String id() {
            return "test.raw";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public java.util.concurrent.CompletionStage capture(Object player) {
            return java.util.concurrent.CompletableFuture.completedFuture(new Object());
        }

        @Override
        public java.util.concurrent.CompletionStage restore(Object player, Object data) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
}
