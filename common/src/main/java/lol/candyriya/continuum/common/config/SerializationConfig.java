package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Snapshot serialization limits.
 *
 * @param gzip            compress snapshot payloads; off by default until measurements say otherwise
 * @param maxSnapshotBytes hard cap for a stored snapshot (after compression when enabled)
 */
@ConfigSerializable
public record SerializationConfig(boolean gzip, int maxSnapshotBytes) {
}
