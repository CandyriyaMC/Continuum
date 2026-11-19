package lol.candyriya.continuum.paper;

import lol.candyriya.continuum.common.config.ConfigLoader;
import lol.candyriya.continuum.common.config.ContinuumConfig;
import lol.candyriya.continuum.common.i18n.Translator;
import lol.candyriya.continuum.paper.config.PlatformConfig;
import lol.candyriya.continuum.paper.config.PlatformConfigLoader;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

import java.nio.file.Path;

/**
 * Loads and validates every file the backend needs before anything else runs.
 *
 * <p>This stage never touches server state — it runs before the plugin object
 * exists. Each file is seeded with bundled defaults when missing, then fully
 * validated; on any validation failure the plugin fails to load with the
 * complete list of problems, instead of enabling half-configured and throwing
 * later at the first player join.</p>
 *
 * <p>On success the validated config, platform config and catalog-backed
 * translator are handed to the plugin through {@link PaperServices}.</p>
 */
public final class ContinuumPaperBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        var logger = context.getLogger();
        Path dataDirectory = context.getDataDirectory();
        String version = context.getConfiguration().getVersion();

        Path configPath = dataDirectory.resolve("config.yml");
        Path platformPath = dataDirectory.resolve("platform.yml");
        if (ConfigLoader.seedIfMissing(configPath)) {
            logger.info("seeded the default configuration at {} — review it before production use", configPath);
        }
        if (PlatformConfigLoader.seedIfMissing(platformPath)) {
            logger.info("seeded the default platform configuration at {}", platformPath);
        }

        ContinuumConfig config;
        PlatformConfig platform;
        try {
            config = ConfigLoader.load(configPath);
            platform = PlatformConfigLoader.load(platformPath);
        } catch (lol.candyriya.continuum.common.config.ConfigurationException e) {
            // the message lists every violation found (paths and rules, never values) — print and abort the load
            logger.error("configuration validation failed; the plugin cannot start:\n{}", e.getMessage());
            throw e;
        }

        // bundled catalogs are part of this jar; a broken jar fails here, not at the first message
        Translator translator = Translator.loadBundled(getClass().getClassLoader(), config.i18n());

        PaperServices.stash(new PaperServices.Handoff(config, platform, translator));
        logger.info("configuration valid: network '{}', this node '{}', default locale {}, version {}",
                config.network().id(), config.network().serverId(), config.i18n().defaultLocale(), version);
    }
}
