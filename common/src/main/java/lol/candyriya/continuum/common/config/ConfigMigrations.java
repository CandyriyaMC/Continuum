package lol.candyriya.continuum.common.config;

import org.spongepowered.configurate.transformation.ConfigurationTransformation;

/**
 * Versioned migrations for config.yml.
 *
 * <p>How it works: the file carries a {@code config-version} key. On load the
 * ConfigManager runs the node through {@link #migrations()}: every version newer than
 * the recorded one applies in order, then the key is updated. A file without the key is
 * treated as version 0 — pre-versioning configs migrate themselves.</p>
 *
 * <p>To add a migration: bump {@link #CURRENT_VERSION} and append
 * {@code .addVersion(N, ...)} — either a ready-made transformation, or
 * {@code .makeVersion(N, builder -> ...)} for key renames/moves.
 * Example: {@code .makeVersion(2, b -> b.addAction(path("old"), (p, n) -> new Object[]{"new"}))}.</p>
 */
public final class ConfigMigrations {

    /** Current config schema version. Grows only upwards. */
    public static final int CURRENT_VERSION = 1;

    private ConfigMigrations() {
    }

    /** All migrations from version 0 up to {@link #CURRENT_VERSION}. */
    public static ConfigurationTransformation.Versioned migrations() {
        return ConfigurationTransformation.versionedBuilder()
                .versionKey("config-version")
                // v1 — base schema, no actions: just stamps the version onto
                // pre-versioning files. Everything above this is a real migration.
                .addVersion(1, ConfigurationTransformation.empty())
                .build();
    }
}
