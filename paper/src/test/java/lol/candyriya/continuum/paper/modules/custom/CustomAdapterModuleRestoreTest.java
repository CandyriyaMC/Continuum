package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomAdapterModuleRestoreTest {

    private static Player player() {
        return FakePlayers.create();
    }

    private static CustomAdapterModule customModule(AdapterRegistry registry) {
        return new CustomAdapterModule(registry);
    }

    private static CustomAdapterModule customModule(AdapterRegistry registry, CustomAdapterLimits limits) {
        return new CustomAdapterModule(registry, new GsonAdapterPayloadCodec(), limits,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                new java.util.concurrent.ScheduledThreadPoolExecutor(1));
    }

    private static AdapterEntry entry(String adapterId, int adapterVersion, String payloadJson,
                                      boolean required, FailurePolicy policy) {
        return new AdapterEntry(adapterId, adapterVersion, required, policy, payloadJson,
                Instant.ofEpochMilli(1731811200000L));
    }

    private static AdapterEntry defaultEntry(String adapterId, String payloadJson) {
        return entry(adapterId, 1, payloadJson, true, FailurePolicy.ABORT);
    }

    private static CustomAdapterPayload payloadOf(List<AdapterEntry> adapters) {
        return new CustomAdapterPayload(CustomAdapterPayload.FORMAT,
                Instant.ofEpochMilli(1731811200000L), adapters, List.of());
    }

    private static final String DTO = "{\"value\":\"captured\",\"count\":7}";

    // ---------- happy path ----------

    @Test
    void restoreDecodesAndAppliesAdapterData() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha");
        registry.register(adapter, "s");
        var module = customModule(registry);
        module.restore(player(), payloadOf(List.of(defaultEntry("test.alpha", DTO))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(new StubAdapter.Payload("captured", 7L)), adapter.restoredPayloads);
        module.close();
    }

    @Test
    void restoreRoundtripThroughCapture() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha");
        registry.register(adapter, "s");
        var module = customModule(registry);
        var p = player();
        var captured = module.capture(p).toCompletableFuture().get(5, TimeUnit.SECONDS);
        module.restore(p, captured).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(new StubAdapter.Payload("captured", 7L)), adapter.restoredPayloads);
        module.close();
    }

    @Test
    void entriesApplyInPlanningOrderNotStoredOrder() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter a = new StubAdapter("test.a").dependencies(List.of("test.b"));
        StubAdapter b = new StubAdapter("test.b");
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        a.onRestore(p -> {
            order.add("a");
            return CompletableFuture.completedFuture(null);
        });
        b.onRestore(p -> {
            order.add("b");
            return CompletableFuture.completedFuture(null);
        });
        registry.register(a, "s");
        registry.register(b, "s");
        var module = customModule(registry);
        // stored with the dependent first — the plan must reorder it behind its dependency
        var payload = payloadOf(List.of(defaultEntry("test.a", DTO), defaultEntry("test.b", DTO)));
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("b", "a"), order);
        module.close();
    }

    // ---------- dependency missing ----------

    @Test
    void missingAdapterWithStoredAbortPolicyAborts() {
        var module = customModule(new AdapterRegistry());
        var payload = payloadOf(List.of(defaultEntry("test.ghost", DTO)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof CustomAdapterRestoreException);
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(1, cause.failures().size());
            assertEquals(AdapterFailure.CODE_DEPENDENCY_MISSING, cause.failures().get(0).code());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void missingAdapterWithStoredSkipPolicyIsSkipped() throws Exception {
        var module = customModule(new AdapterRegistry());
        var payload = payloadOf(List.of(
                entry("test.ghost", 1, DTO, false, FailurePolicy.SKIP)));
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        module.close();
    }

    @Test
    void optionalMissingAdapterDoesNotBlockOthers() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter good = new StubAdapter("test.good");
        registry.register(good, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(
                entry("test.ghost", 1, DTO, false, FailurePolicy.SKIP),
                defaultEntry("test.good", DTO)));
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, good.restoredPayloads.size());
        module.close();
    }

    // ---------- migration routing ----------

    @Test
    void olderPayloadRoutedThroughMigrate() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").version(2).onMigrate((fromVersion, raw) -> {
            assertEquals(1, fromVersion);
            assertEquals(DTO, raw);
            return CompletableFuture.completedFuture(new StubAdapter.Payload("migrated", 9L));
        });
        registry.register(adapter, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(entry("test.alpha", 1, DTO, true, FailurePolicy.ABORT)));
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(new StubAdapter.Payload("migrated", 9L)), adapter.restoredPayloads);
        assertEquals(1, adapter.migratedFrom.size());
        assertTrue(adapter.migratedFrom.get(0).startsWith("1:"));
        module.close();
    }

    @Test
    void migrationFailureAbortsRequiredAdapter() {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").version(2).onMigrate((fromVersion, raw) ->
                CompletableFuture.failedFuture(new lol.candyriya.continuum.api.module.AdapterMigrationException(
                        "test.alpha", fromVersion, "incompatible shape")));
        registry.register(adapter, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(entry("test.alpha", 1, DTO, true, FailurePolicy.ABORT)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required migration failure must abort the restore");
        } catch (ExecutionException e) {
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(AdapterFailure.CODE_MIGRATION_FAILED, cause.failures().get(0).code());
            assertTrue(adapter.restoredPayloads.isEmpty(), "restore must not run after a failed migration");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void migrationFailureSkipsOptionalAdapter() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").optional().version(2).onMigrate(
                (fromVersion, raw) -> CompletableFuture.failedFuture(
                        new lol.candyriya.continuum.api.module.AdapterMigrationException("test.alpha", fromVersion, "nope")));
        registry.register(adapter, "s");
        var other = new StubAdapter("test.other");
        registry.register(other, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(
                entry("test.alpha", 1, DTO, false, FailurePolicy.SKIP),
                defaultEntry("test.other", DTO)));
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(adapter.restoredPayloads.isEmpty());
        assertEquals(1, other.restoredPayloads.size());
        module.close();
    }

    @Test
    void newerStoredVersionRejectedBeforeMigrateIsCalled() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").optional().version(1);
        registry.register(adapter, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(entry("test.alpha", 2, DTO, false, FailurePolicy.SKIP)));
        // optional → isolated, migrate never invoked
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(adapter.migratedFrom.isEmpty());
        module.close();
    }

    @Test
    void newerStoredVersionAbortsRequiredAdapter() {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").version(1);
        registry.register(adapter, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(entry("test.alpha", 2, DTO, true, FailurePolicy.ABORT)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a newer stored payload must abort the restore");
        } catch (ExecutionException e) {
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(AdapterFailure.CODE_MIGRATION_FAILED, cause.failures().get(0).code());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void migrationTimeoutRecordsTimeoutCode() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter adapter = new StubAdapter("test.alpha").optional().timeout(Duration.ofMillis(50)).version(2)
                .onMigrate((fromVersion, raw) -> new CompletableFuture<>());
        registry.register(adapter, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(entry("test.alpha", 1, DTO, false, FailurePolicy.SKIP)));
        // restore completes; timeout detach drops the migrate
        module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(adapter.restoredPayloads.isEmpty());
        module.close();
    }

    // ---------- validation ----------

    @Test
    void validateInvalidIsolatesOptionalAdapter() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").optional().onValidate(
                p -> CompletableFuture.completedFuture(ValidationResult.invalid("nope")));
        StubAdapter good = new StubAdapter("test.good");
        registry.register(bad, "s");
        registry.register(good, "s");
        var module = customModule(registry);
        module.restore(player(), payloadOf(List.of(
                        defaultEntry("test.bad", DTO), defaultEntry("test.good", DTO))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(bad.restoredPayloads.isEmpty(), "invalid payload must not reach restore");
        assertEquals(1, good.restoredPayloads.size());
        module.close();
    }

    @Test
    void validateInvalidAbortsRequiredAdapter() {
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").onValidate(
                p -> CompletableFuture.completedFuture(ValidationResult.invalid("nope")));
        StubAdapter later = new StubAdapter("test.later");
        registry.register(bad, "s");
        registry.register(later, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(defaultEntry("test.bad", DTO), defaultEntry("test.later", DTO)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required validation failure must abort the restore");
        } catch (ExecutionException e) {
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(AdapterFailure.CODE_VALIDATION_FAILED, cause.failures().get(0).code());
            assertTrue(later.restoredPayloads.isEmpty(), "entries after the aborted one must not apply");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void validateThrowingIsIsolatedPerPolicy() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").optional().onValidate(
                p -> {
                    throw new IllegalStateException("validate boom");
                });
        registry.register(bad, "s");
        var module = customModule(registry);
        module.restore(player(), payloadOf(List.of(defaultEntry("test.bad", DTO))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(bad.restoredPayloads.isEmpty());
        module.close();
    }

    // ---------- restore failures ----------

    @Test
    void restoreThrowingAbortsRequiredAdapter() {
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").onRestore(p -> {
            throw new IllegalStateException("restore boom");
        });
        registry.register(bad, "s");
        var module = customModule(registry);
        var payload = payloadOf(List.of(defaultEntry("test.bad", DTO)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required restore failure must abort the restore");
        } catch (ExecutionException e) {
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(AdapterFailure.CODE_RESTORE_FAILED, cause.failures().get(0).code());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void restoreThrowingIsolatedOptionalAdapter() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").optional().onRestore(p -> {
            throw new IllegalStateException("restore boom");
        });
        StubAdapter good = new StubAdapter("test.good");
        registry.register(bad, "s");
        registry.register(good, "s");
        var module = customModule(registry);
        module.restore(player(), payloadOf(List.of(
                        defaultEntry("test.bad", DTO), defaultEntry("test.good", DTO))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, good.restoredPayloads.size());
        module.close();
    }

    @Test
    void restoreTimeoutDetaches() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter slow = new StubAdapter("test.slow").optional().timeout(Duration.ofMillis(50))
                .onRestore(p -> new CompletableFuture<>());
        StubAdapter good = new StubAdapter("test.good");
        registry.register(slow, "s");
        registry.register(good, "s");
        var module = customModule(registry);
        module.restore(player(), payloadOf(List.of(
                        defaultEntry("test.slow", DTO), defaultEntry("test.good", DTO))))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, good.restoredPayloads.size());
        module.close();
    }

    // ---------- size governance at restore ----------

    @Test
    void oversizedStoredEntryRejectedBeforeAdapterRuns() {
        var registry = new AdapterRegistry();
        StubAdapter greedy = new StubAdapter("test.greedy");
        registry.register(greedy, "s");
        var module = customModule(registry, new CustomAdapterLimits(16, 256 * 1024));
        var payload = payloadOf(List.of(entry("test.greedy", 1, "{\"value\":\"" + "x".repeat(64) + "\",\"count\":1}",
                true, FailurePolicy.ABORT)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("an oversized stored entry must be refused");
        } catch (ExecutionException e) {
            var cause = (CustomAdapterRestoreException) e.getCause();
            assertEquals(AdapterFailure.CODE_VALIDATION_FAILED, cause.failures().get(0).code());
            assertTrue(greedy.restoredPayloads.isEmpty());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }

    @Test
    void oversizedTotalPayloadRefusedBeforeAnyEntryApplies() {
        var registry = new AdapterRegistry();
        StubAdapter good = new StubAdapter("test.good");
        registry.register(good, "s");
        var module = customModule(registry, new CustomAdapterLimits(90, 100));
        String bigDto = "{\"value\":\"" + "x".repeat(50) + "\",\"count\":7}";
        var payload = payloadOf(List.of(
                defaultEntry("test.good", bigDto), entry("test.ghost", 1, bigDto, false, FailurePolicy.SKIP)));
        try {
            module.restore(player(), payload).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("an oversized total payload must be refused");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PayloadTooLargeException);
            assertTrue(good.restoredPayloads.isEmpty(), "no adapter may run past the total cap");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        module.close();
    }
}
