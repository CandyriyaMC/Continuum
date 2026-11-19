package lol.candyriya.continuum.paper.modules.custom;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import lol.candyriya.continuum.api.module.FailurePolicy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomAdapterModuleCaptureTest {

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

    // ---------- happy path ----------

    @Test
    void emptyRegistryCapturesEmptyPayload() throws Exception {
        var module = customModule(new AdapterRegistry());
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(CustomAdapterPayload.FORMAT, payload.format());
        assertTrue(payload.adapters().isEmpty());
        assertTrue(payload.failures().isEmpty());
        assertNotNull(payload.capturedAt());
        module.close();
    }

    @Test
    void captureRoundtripsAdapterData() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.alpha"), "test-source");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.adapters().size());
        var entry = payload.adapters().get(0);
        assertEquals("test.alpha", entry.adapterId());
        assertEquals(1, entry.adapterVersion());
        assertTrue(entry.required());
        assertEquals(FailurePolicy.ABORT, entry.failurePolicy());
        assertNotNull(entry.capturedAt());
        // the payload string is exactly what gets persisted; gson must roundtrip it
        StubAdapter.Payload decoded = new com.google.gson.Gson()
                .fromJson(entry.payload(), StubAdapter.Payload.class);
        assertEquals(new StubAdapter.Payload("captured", 7L), decoded);
        module.close();
    }

    @Test
    void captureEntriesFollowPlanningOrder() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter zeta = new StubAdapter("test.zeta");
        zeta.priority(10);
        StubAdapter alpha = new StubAdapter("test.alpha");
        alpha.priority(20);
        registry.register(alpha, "s");
        registry.register(zeta, "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("test.zeta", "test.alpha"),
                payload.adapters().stream().map(AdapterEntry::adapterId).toList());
        module.close();
    }

    // ---------- failure policy matrix ----------

    @Test
    void optionalCaptureExceptionIsIsolated() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").optional().onCapture(
                p -> CompletableFuture.failedFuture(new RuntimeException("boom"))), "s");
        registry.register(new StubAdapter("test.good"), "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.adapters().size());
        assertEquals("test.good", payload.adapters().get(0).adapterId());
        assertEquals(1, payload.failures().size());
        var failure = payload.failures().get(0);
        assertEquals("test.bad", failure.adapterId());
        assertEquals(AdapterFailure.CODE_CAPTURE_FAILED, failure.code());
        assertTrue(failure.reason().contains("boom"));
        module.close();
    }

    @Test
    void requiredCaptureExceptionAborts() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").onCapture(
                p -> CompletableFuture.failedFuture(new RuntimeException("boom"))), "s");
        registry.register(new StubAdapter("test.good"), "s");
        var module = customModule(registry);
        try {
            module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required adapter must abort the capture");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof CustomAdapterCaptureException);
            var cause = (CustomAdapterCaptureException) e.getCause();
            assertEquals("test.bad", cause.failures().get(0).adapterId());
            assertEquals(AdapterFailure.CODE_CAPTURE_FAILED, cause.failures().get(0).code());
        }
        module.close();
    }

    @Test
    void optionalAdapterThrowingSynchronouslyIsIsolated() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").optional().onCapture(
                p -> {
                    throw new IllegalStateException("sync boom");
                }), "s");
        registry.register(new StubAdapter("test.good"), "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.adapters().size());
        assertEquals("test.good", payload.adapters().get(0).adapterId());
        assertEquals(1, payload.failures().size());
        assertTrue(payload.failures().get(0).reason().contains("sync boom"));
        module.close();
    }

    @Test
    void requiredAdapterThrowingSynchronouslyAborts() {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").onCapture(
                p -> {
                    throw new IllegalStateException("sync boom");
                }), "s");
        var module = customModule(registry);
        assertThrows(ExecutionException.class,
                () -> module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS));
        module.close();
    }

    @Test
    void adapterReturningNullStageFails() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").optional().onCapture(p -> null), "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.failures().size());
        assertEquals(AdapterFailure.CODE_CAPTURE_FAILED, payload.failures().get(0).code());
        assertTrue(payload.failures().get(0).reason().contains("null instead of a stage"));
        module.close();
    }

    @Test
    void adapterCompletingWithNullValueFails() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").optional().onCapture(
                p -> CompletableFuture.completedFuture(null)), "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.failures().size());
        assertTrue(payload.failures().get(0).reason().contains("null payload"));
        module.close();
    }

    @Test
    void optionalAdapterIsolatedRegardlessOfPlannedPosition() throws Exception {
        // bad adapter completes first (priority 10), good adapter second — the failure
        // must not leak into the good adapter's entry either way
        var registry = new AdapterRegistry();
        StubAdapter bad = new StubAdapter("test.bad").optional().priority(10).onCapture(
                p -> CompletableFuture.failedFuture(new RuntimeException("boom")));
        StubAdapter good = new StubAdapter("test.good").priority(20);
        registry.register(good, "s");
        registry.register(bad, "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("test.good"), payload.adapters().stream().map(AdapterEntry::adapterId).toList());
        assertEquals(List.of("test.bad"), payload.failures().stream().map(AdapterFailure::adapterId).toList());
        module.close();
    }

    // ---------- timeout path ----------

    @Test
    void timeoutDetachesAndRecordsOptionalAdapter() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.slow").optional().timeout(Duration.ofMillis(50)).onCapture(
                p -> new CompletableFuture<>()), "s");
        registry.register(new StubAdapter("test.fast"), "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("test.fast"), payload.adapters().stream().map(AdapterEntry::adapterId).toList());
        var failure = payload.failures().stream()
                .filter(f -> f.adapterId().equals("test.slow")).findFirst().orElseThrow();
        assertEquals(AdapterFailure.CODE_TIMEOUT, failure.code());
        module.close();
    }

    @Test
    void timeoutAbortsRequiredAdapter() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.slow").timeout(Duration.ofMillis(50)).onCapture(
                p -> new CompletableFuture<>()), "s");
        var module = customModule(registry);
        try {
            module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required adapter must abort the capture");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof CustomAdapterCaptureException);
            var cause = (CustomAdapterCaptureException) e.getCause();
            assertEquals(AdapterFailure.CODE_TIMEOUT, cause.failures().get(0).code());
        }
        module.close();
    }

    @Test
    void lateWritesAfterDeadlineAreRefused() throws Exception {
        var writes = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
        var player = FakePlayers.create(writes);
        var registry = new AdapterRegistry();
        var captureFuture = new CompletableFuture<StubAdapter.Payload>();
        var writeFailure = new AtomicReference<Throwable>();
        StubAdapter slow = new StubAdapter("test.slow").optional().timeout(Duration.ofMillis(50));
        slow.onCapture(p -> {
            // the adapter holds onto the guarded player and, once its stage fires long after
            // the deadline, tries to write — the guard must refuse
            captureFuture.whenComplete((value, error) -> {
                try {
                    p.setFoodLevel(3);
                } catch (Throwable t) {
                    writeFailure.set(t);
                }
            });
            return captureFuture;
        });
        registry.register(slow, "s");
        var module = customModule(registry);
        var payload = module.capture(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.failures().size());
        // the stage completes way past the deadline; the player write must bounce off the guard
        captureFuture.complete(new StubAdapter.Payload("late", 1L));
        assertTrue(writeFailure.get() instanceof AdapterCancelledException,
                () -> "expected AdapterCancelledException, got " + writeFailure.get());
        assertTrue(writes.isEmpty(), "the late write must not have landed");
        module.close();
    }

    @Test
    void lateCompletionDoesNotCorruptAssembledPayload() throws Exception {
        var registry = new AdapterRegistry();
        var captureFuture = new CompletableFuture<StubAdapter.Payload>();
        StubAdapter slow = new StubAdapter("test.slow").optional().timeout(Duration.ofMillis(50));
        slow.onCapture(p -> captureFuture);
        registry.register(slow, "s");
        var module = customModule(registry);
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(payload.adapters().isEmpty());
        captureFuture.complete(new StubAdapter.Payload("late", 99L));
        // payload is immutable and already assembled; the late value has nowhere to land
        assertTrue(payload.adapters().isEmpty());
        module.close();
    }

    // ---------- size governance ----------

    @Test
    void perAdapterCapDropsOptionalAdapterAndRecordsIt() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter greedy = new StubAdapter("test.greedy").optional().onCapture(
                p -> CompletableFuture.completedFuture(new StubAdapter.Payload("x".repeat(1024), 1L)));
        registry.register(greedy, "s");
        registry.register(new StubAdapter("test.good"), "s");
        var module = customModule(registry, new CustomAdapterLimits(48, 256 * 1024));
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("test.good"), payload.adapters().stream().map(AdapterEntry::adapterId).toList());
        var failure = payload.failures().stream()
                .filter(f -> f.adapterId().equals("test.greedy")).findFirst().orElseThrow();
        assertEquals(AdapterFailure.CODE_PAYLOAD_TOO_LARGE, failure.code());
        module.close();
    }

    @Test
    void perAdapterCapAbortsRequiredAdapter() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter greedy = new StubAdapter("test.greedy").onCapture(
                p -> CompletableFuture.completedFuture(new StubAdapter.Payload("x".repeat(1024), 1L)));
        registry.register(greedy, "s");
        var module = customModule(registry, new CustomAdapterLimits(16, 256 * 1024));
        try {
            module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("a required adapter beyond the per-adapter cap must abort");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof CustomAdapterCaptureException);
            var cause = (CustomAdapterCaptureException) e.getCause();
            assertEquals(AdapterFailure.CODE_PAYLOAD_TOO_LARGE, cause.failures().get(0).code());
        }
        module.close();
    }

    @Test
    void totalCapFailsCaptureHard() throws Exception {
        var registry = new AdapterRegistry();
        StubAdapter greedy = new StubAdapter("test.greedy").onCapture(
                p -> CompletableFuture.completedFuture(new StubAdapter.Payload("x".repeat(32), 1L)));
        registry.register(greedy, "s");
        StubAdapter greedy2 = new StubAdapter("test.greedy2").onCapture(
                p -> CompletableFuture.completedFuture(new StubAdapter.Payload("y".repeat(32), 2L)));
        registry.register(greedy2, "s");
        var module = customModule(registry, new CustomAdapterLimits(64, 100));
        try {
            module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("the total cap must fail the capture hard");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PayloadTooLargeException);
            var tooLarge = (PayloadTooLargeException) e.getCause();
            assertEquals(100, tooLarge.capBytes());
            assertTrue(tooLarge.actualBytes() > 100);
        }
        module.close();
    }

    @Test
    void nonEncodablePayloadRecordedAsCaptureFailed() throws Exception {
        var registry = new AdapterRegistry();
        registry.register(new StubAdapter("test.bad").optional(), "s");
        var broken = new AdapterPayloadCodec() {
            @Override
            public String encode(Object payload, java.lang.reflect.Type payloadType) {
                throw new IllegalStateException("nope");
            }

            @Override
            public Object decode(String payload, java.lang.reflect.Type payloadType) {
                return null;
            }
        };
        var module = new CustomAdapterModule(registry, broken, CustomAdapterLimits.DEFAULT,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                new java.util.concurrent.ScheduledThreadPoolExecutor(1));
        var payload = module.capture(player()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, payload.failures().size());
        assertEquals(AdapterFailure.CODE_CAPTURE_FAILED, payload.failures().get(0).code());
        assertTrue(payload.failures().get(0).reason().contains("not JSON-encodable"));
        module.close();
    }
}
