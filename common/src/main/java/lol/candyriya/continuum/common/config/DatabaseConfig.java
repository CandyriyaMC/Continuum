package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Database selection and settings for every supported engine.
 *
 * <p>Both blocks are always parsed; only the one selected by {@link #type} is
 * semantically validated and used at runtime.</p>
 */
@ConfigSerializable
public record DatabaseConfig(DatabaseType type, MysqlSettings mysql, MongoSettings mongodb) {

    public DatabaseConfig {
        if (mysql == null) {
            mysql = new MysqlSettings("", 3306, "", "", "", new MysqlPool(10, 2, java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(30)));
        }
        if (mongodb == null) {
            mongodb = new MongoSettings("", "", new MongoPool(50, 2, java.time.Duration.ofSeconds(5)));
        }
    }
}
