package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.time.Duration;

/**
 * HikariCP pool tuning for MySQL/MariaDB.
 *
 * @param maximumPoolSize  upper bound of connections handed out at once
 * @param minimumIdle      idle connections HikariCP keeps alive
 * @param connectionTimeout how long a caller waits for a connection before failing
 * @param maxLifetime      max time a connection lives before being retired and replaced
 */
@ConfigSerializable
public record MysqlPool(int maximumPoolSize, int minimumIdle, Duration connectionTimeout, Duration maxLifetime) {
}
