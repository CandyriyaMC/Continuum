package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;

/**
 * MongoDB driver pool tuning.
 *
 * @param maxSize     upper bound of connections in the driver's pool
 * @param minSize     idle connections to keep warm
 * @param maxWaitTime how long a caller waits for a connection before failing
 */
@ConfigSerializable
public record MongoPool(int maxSize, int minSize, Duration maxWaitTime) {
}
