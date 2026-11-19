package lol.candyriya.continuum.api;

import lol.candyriya.continuum.api.event.EventService;
import lol.candyriya.continuum.api.event.ContinuumEvent;
import lol.candyriya.continuum.api.history.HistoryService;
import lol.candyriya.continuum.api.module.ModuleService;
import lol.candyriya.continuum.api.player.PlayerService;
import lol.candyriya.continuum.api.session.SessionService;
import lol.candyriya.continuum.api.snapshot.SnapshotService;

/**
 * Minimal functional-but-real {@link ContinuumApi} for provider tests: every sub-service
 * returns a working implementation, not a stub that blows up. The provider never calls
 * them in these tests; they exist so the fake complies with the interface's nullability
 * contract on both sides.
 */
final class FakeContinuumApi implements ContinuumApi {

    private final String version;
    private final SessionService sessions =
            new SessionService() {
                @Override
                public java.util.concurrent.CompletableFuture<java.util.Optional<lol.candyriya.continuum.api.session.SessionInfo>> session(java.util.UUID playerId) {
                    throw new AssertionError("not part of this test");
                }

                @Override
                public java.util.concurrent.CompletableFuture<java.util.List<lol.candyriya.continuum.api.session.SessionInfo>> activeOn(String serverId) {
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
                }
            };
    private final SnapshotService snapshots =
            new SnapshotService() {
                @Override
                public java.util.concurrent.CompletableFuture<lol.candyriya.continuum.api.report.CaptureReport> capture(
                        java.util.UUID playerId, lol.candyriya.continuum.api.snapshot.SnapshotReason reason) {
                    throw new AssertionError("not part of this test");
                }

                @Override
                public java.util.concurrent.CompletableFuture<java.util.Optional<lol.candyriya.continuum.api.snapshot.PlayerSnapshot>> latest(java.util.UUID playerId) {
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
                }

                @Override
                public java.util.concurrent.CompletableFuture<java.util.Optional<lol.candyriya.continuum.api.snapshot.PlayerSnapshot>> get(java.util.UUID playerId, long snapshotId) {
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
                }
            };
    private final ModuleService modules =
            new ModuleService() {
                private final java.util.List<lol.candyriya.continuum.api.module.ModuleInfo> registry =
                        new java.util.concurrent.CopyOnWriteArrayList<>();

                @Override
                public <T> lol.candyriya.continuum.api.module.ModuleRegistration register(
                        lol.candyriya.continuum.api.module.SyncAdapter<?, T> adapter) {
                    lol.candyriya.continuum.api.module.ModuleContracts.validate(adapter);
                    if (registry.stream().anyMatch(module -> module.id().equals(adapter.id()))) {
                        throw new lol.candyriya.continuum.api.module.DuplicateModuleIdException(adapter.id());
                    }
                    registry.add(lol.candyriya.continuum.api.module.ModuleInfo.of(adapter, "test"));
                    return new lol.candyriya.continuum.api.module.ModuleRegistration() {
                        private volatile boolean active = true;

                        @Override
                        public String id() {
                            return adapter.id();
                        }

                        @Override
                        public void unregister() {
                            active = false;
                            registry.removeIf(module -> module.id().equals(adapter.id()));
                        }

                        @Override
                        public boolean isActive() {
                            return active;
                        }
                    };
                }

                @Override
                public java.util.List<lol.candyriya.continuum.api.module.ModuleInfo> modules() {
                    return java.util.List.copyOf(registry);
                }

                @Override
                public java.util.Optional<lol.candyriya.continuum.api.module.ModuleInfo> module(String id) {
                    return registry.stream().filter(module -> module.id().equals(id)).findFirst();
                }
            };
    private final PlayerService players =
            playerId -> java.util.concurrent.CompletableFuture.completedFuture(
                    new lol.candyriya.continuum.api.player.PlayerState(null, null));
    private final HistoryService history =
            new HistoryService() {
                @Override
                public java.util.concurrent.CompletableFuture<java.util.List<lol.candyriya.continuum.api.snapshot.PlayerSnapshot>> list(
                        java.util.UUID playerId, int limit) {
                    return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of());
                }

                @Override
                public java.util.concurrent.CompletableFuture<lol.candyriya.continuum.api.report.RestoreReport> rollback(
                        java.util.UUID playerId, long snapshotId) {
                    throw new AssertionError("not part of this test");
                }
            };
    private final EventService events =
            new EventService() {
                @Override
                public <E extends ContinuumEvent> lol.candyriya.continuum.api.event.EventSubscription subscribe(
                        Class<E> type, lol.candyriya.continuum.api.event.ContinuumEventListener<E> listener) {
                    return new lol.candyriya.continuum.api.event.EventSubscription() {
                        private volatile boolean active = true;

                        @Override
                        public void unsubscribe() {
                            active = false;
                        }

                        @Override
                        public boolean isActive() {
                            return active;
                        }
                    };
                }
            };

    FakeContinuumApi(String version) {
        this.version = version;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public SessionService sessions() {
        return sessions;
    }

    @Override
    public SnapshotService snapshots() {
        return snapshots;
    }

    @Override
    public ModuleService modules() {
        return modules;
    }

    @Override
    public PlayerService players() {
        return players;
    }

    @Override
    public HistoryService history() {
        return history;
    }

    @Override
    public EventService events() {
        return events;
    }
}
