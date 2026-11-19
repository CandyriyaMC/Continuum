package lol.candyriya.continuum.velocity.command;

import lol.candyriya.continuum.velocity.session.NetworkSessionRegistry;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry-driven inspection: everything here comes from the proxy's own
 * connection events, so it stays consistent with what the network actually
 * did. Storage-backed fields (last snapshot, fencing token) are reported as
 * {@link PlayerInspectionService.SnapshotAvailability#NOT_CONFIGURED} until
 * the storage path is connected on this proxy; no call site should invent
 * values for them.
 */
public final class RegistryPlayerInspectionService implements PlayerInspectionService {

    private final NetworkSessionRegistry registry;

    public RegistryPlayerInspectionService(NetworkSessionRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<PlayerSessionView> inspect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return registry.snapshot(playerId).map(snapshot -> new PlayerSessionView(
                snapshot.playerId(),
                snapshot.backendName(),
                snapshot.awaitingBackend(),
                snapshot.awaitingReason(),
                snapshot.connectedAt(),
                snapshot.transitionsInWindow(),
                SnapshotAvailability.NOT_CONFIGURED));
    }
}
