package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * MongoDB connection settings.
 *
 * @param uri      full connection string; may embed credentials, never logged
 * @param database database to use inside the deployment
 * @param pool     driver pool tuning
 */
@ConfigSerializable
public record MongoSettings(String uri, String database, MongoPool pool) {

    @Override
    public String toString() {
        // uri may embed user:password@host — keep the host part out of tickets too
        return "MongoSettings[uri=<masked>, database=%s, pool=%s]".formatted(database, pool);
    }
}
