package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.FailurePolicy;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomAdapterModuleValidateTest {

    private static Player player() {
        return FakePlayers.create();
    }

    private static CustomAdapterModule module(CustomAdapterLimits limits) {
        return new CustomAdapterModule(new AdapterRegistry(), new GsonAdapterPayloadCodec(), limits,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                new java.util.concurrent.ScheduledThreadPoolExecutor(1));
    }

    private static AdapterEntry entry(String adapterId, String payload) {
        return new AdapterEntry(adapterId, 1, true, FailurePolicy.ABORT, payload,
                Instant.ofEpochMilli(1731811200000L));
    }

    private static CustomAdapterPayload payloadOf(List<AdapterEntry> entries) {
        return new CustomAdapterPayload(CustomAdapterPayload.FORMAT,
                Instant.ofEpochMilli(1731811200000L), entries, List.of());
    }

    @Test
    void validPayloadPasses() throws Exception {
        var module = module(CustomAdapterLimits.DEFAULT);
        var result = module.validate(player(), payloadOf(List.of(entry("test.alpha", "{\"k\":1}"))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.valid());
        module.close();
    }

    @Test
    void emptyPayloadPasses() throws Exception {
        var module = module(CustomAdapterLimits.DEFAULT);
        var result = module.validate(player(), payloadOf(List.of()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.valid());
        module.close();
    }

    @Test
    void wrongFormatRejected() throws Exception {
        var module = module(CustomAdapterLimits.DEFAULT);
        var broken = new CustomAdapterPayload(99,
                Instant.ofEpochMilli(1731811200000L), List.of(), List.of());
        var result = module.validate(player(), broken).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("format"));
        module.close();
    }

    @Test
    void oversizedEntryRejected() throws Exception {
        var module = module(new CustomAdapterLimits(16, 256 * 1024));
        var result = module.validate(player(), payloadOf(List.of(entry("test.greedy", "{\"k\":\"" + "x".repeat(64) + "\"}"))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("per-adapter"));
        module.close();
    }

    @Test
    void oversizedTotalRejected() throws Exception {
        var module = module(new CustomAdapterLimits(90, 100));
        String bigDto = "{\"k\":\"" + "x".repeat(50) + "\"}";
        var result = module.validate(player(), payloadOf(List.of(
                        entry("test.alpha", bigDto),
                        entry("test.beta", bigDto))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertFalse(result.valid());
        assertTrue(result.reason().contains("total"));
        module.close();
    }

    @Test
    void duplicateEntriesRejectedAtConstruction() {
        // duplicate adapter ids cannot exist in a payload — the record rejects them
        assertThrows(IllegalArgumentException.class, () -> payloadOf(
                List.of(entry("test.alpha", "{\"k\":1}"), entry("test.alpha", "{\"k\":2}"))));
    }

    @Test
    void moduleIdIsTheReservedFirstPartyId() {
        var module = module(CustomAdapterLimits.DEFAULT);
        assertEquals("continuum.custom", module.id());
        assertEquals(lol.candyriya.continuum.api.module.SyncModule.DEFAULT_PRIORITY, module.priority());
        module.close();
    }

    @Test
    void dependenciesAreEmptyWithoutFirstPartyContext() {
        var module = module(CustomAdapterLimits.DEFAULT);
        assertEquals(List.of(), module.dependencies());
        module.close();
    }

    @Test
    void dependenciesReportKnownFirstPartyDependenciesOnly() {
        var registry = new AdapterRegistry(List.of(
                new FirstPartyModule(lol.candyriya.continuum.api.module.BuiltinModules.VITALS, 100, List.of())));
        StubAdapter dependent = new StubAdapter("test.alpha");
        dependent.dependencies(List.of(
                lol.candyriya.continuum.api.module.BuiltinModules.VITALS,
                "continuum.ghost"));
        registry.register(dependent, "s");
        var module = module(registry);
        assertEquals(List.of(lol.candyriya.continuum.api.module.BuiltinModules.VITALS), module.dependencies());
        module.close();
    }

    private static CustomAdapterModule module(AdapterRegistry registry) {
        return new CustomAdapterModule(registry);
    }
}
