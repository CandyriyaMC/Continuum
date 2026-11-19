package lol.candyriya.continuum.paper.orchestration;

import lol.candyriya.continuum.common.config.ConfigLoader;
import lol.candyriya.continuum.common.config.ContinuumConfig;

import lol.candyriya.continuum.api.module.BuiltinModules;
import lol.candyriya.continuum.api.module.FailurePolicy;
import lol.candyriya.continuum.api.module.SyncModule;
import lol.candyriya.continuum.api.module.ValidationResult;

import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared config + fake modules for the pipeline tests. */
final class TestSupport {

    private TestSupport() {
    }

    static ContinuumConfig fullConfig() {
        return ConfigLoader.load("""
                debug: false
                network:
                  id: test-network
                  server-id: test-node
                  role: backend
                database:
                  type: mysql
                  mysql:
                    host: localhost
                    port: 3306
                    database: continuum
                    username: continuum
                    password: ""
                    pool:
                      maximum-pool-size: 10
                      minimum-idle: 2
                      connection-timeout: 5s
                      max-lifetime: 30m
                messaging:
                  rabbitmq:
                    enabled: true
                    uri: "amqp://localhost:5672/"
                    exchange: continuum.events
                    retry:
                      max-attempts: 5
                      initial-backoff: 200ms
                      max-backoff: 10s
                  plugin-message:
                    enabled: true
                    channel: continuum:main
                    max-payload-bytes: 65536
                sync:
                  autosave:
                    enabled: true
                    interval: 60s
                  modules:
                    inventory: true
                    ender-chest: true
                    vitals: true
                    experience: true
                    effects: true
                    attributes: true
                    location: true
                    gamemode: true
                    advancements: true
                    statistics: true
                    recipes: true
                    pdc: true
                    custom: true
                  transfer:
                    require-snapshot: true
                    restore-before-play: true
                  recovery:
                    enabled: true
                    interval: 30s
                    restore-order: []
                i18n:
                  default-locale: en_US
                  fallback-locale: en_US
                  use-client-locale: true
                serialization:
                  gzip: false
                  max-snapshot-bytes: 8388608
                """);
    }

    /** A deterministic module for pipeline tests; counts how often capture ran. */
    static final class FakeModule implements SyncModule<Player, TestPayload> {

        private final String id;
        private final boolean captureFails;
        private final AtomicInteger captureCalls = new AtomicInteger();
        private final AtomicInteger restoreCalls = new AtomicInteger();

        FakeModule(String id, boolean captureFails) {
            this.id = id;
            this.captureFails = captureFails;
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
        public CompletionStage<TestPayload> capture(Player player) {
            captureCalls.incrementAndGet();
            if (captureFails) {
                return CompletableFuture.failedFuture(new IllegalStateException(id + " cannot capture"));
            }
            return CompletableFuture.completedFuture(new TestPayload(1, id + "-marker"));
        }

        @Override
        public CompletionStage<Void> restore(Player player, TestPayload data) {
            restoreCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<ValidationResult> validate(Player player, TestPayload data) {
            return CompletableFuture.completedFuture(ValidationResult.ok());
        }

        int captureCalls() {
            return captureCalls.get();
        }
    }

    static ModuleRuntime.RegisteredModule spec(String id, boolean captureFails, FailurePolicy policy) {
        return new ModuleRuntime.RegisteredModule(id, new FakeModule(id, captureFails), TestPayload.class, policy, true);
    }

    record TestPayload(int format, String marker) {
    }
}
