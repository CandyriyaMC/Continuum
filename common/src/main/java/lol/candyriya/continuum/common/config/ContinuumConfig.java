package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * Root of the shared configuration, mapped from {@code config.yml}.
 *
 * <p>Semantic validation beyond type checks lives in {@link ConfigLoader}:
 * type mapping catches "port is not a number", the loader catches "port is
 * 99999" and "mongodb uri is blank while type is mongodb" with one error
 * report listing every problem.</p>
 */
@ConfigSerializable
public record ContinuumConfig(boolean debug,
                              NetworkConfig network,
                              DatabaseConfig database,
                              MessagingConfig messaging,
                              SyncConfig sync,
                              I18nConfig i18n,
                              SerializationConfig serialization) {

    public ContinuumConfig {
        if (network == null) {
            network = new NetworkConfig("", "", ServerRole.BACKEND);
        }
        if (database == null) {
            database = new DatabaseConfig(DatabaseType.MYSQL, null, null);
        }
        if (messaging == null) {
            messaging = new MessagingConfig(null, null);
        }
        if (sync == null) {
            sync = new SyncConfig(null, null, null, null);
        }
        if (i18n == null) {
            i18n = new I18nConfig("en_US", "en_US", true);
        }
        if (serialization == null) {
            serialization = new SerializationConfig(false, 8 * 1024 * 1024);
        }
    }
}
