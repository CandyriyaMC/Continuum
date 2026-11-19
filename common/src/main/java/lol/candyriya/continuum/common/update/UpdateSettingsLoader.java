package lol.candyriya.continuum.common.update;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Seeds and reads {@code update.yml}, the one file the updater owns.
 *
 * <p>The file is deliberately separate from {@code config.yml}: the shared
 * configuration is a closed schema that a node never gets to extend, while
 * the updater needs a place for an operator to drop a license token without
 * editing the network-wide config. Missing file is not an error - defaults
 * are written and used. Broken file disables the updater for the run and
 * lists every problem found, mirroring the shared loader's failure style;
 * nothing else on the server is affected.</p>
 *
 * <p>Token resolution order: {@code updates.license-token} in
 * {@code update.yml} wins; when it is blank, a raw lenient probe of
 * {@code config.yml} looks for {@code license.token} (the shape the license
 * service documents). The probe never fails the load - a malformed or
 * unreadable {@code config.yml} can never disable the plugin through this
 * path.</p>
 */
public final class UpdateSettingsLoader {

    public static final String FILE_NAME = "update.yml";

    private static final Pattern HOST_SUFFIX = Pattern.compile("^\\.?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    private UpdateSettingsLoader() {
    }

    /**
     * Loads the updater settings for {@code dataDirectory}.
     *
     * @param dataDirectory    the plugin's data directory (holds {@code update.yml} and, for the probe, {@code config.yml})
     * @param sharedConfigPath the shared config probed for a {@code license.token} fallback; ignored when null
     * @param logger           where the single "seeded" / "rejected" lines go
     * @return usable settings, or a disabled holder listing every problem found
     */
    public static UpdateSettings load(Path dataDirectory, Path sharedConfigPath, Logger logger) {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                Files.writeString(file, template());
                logger.info("seeded {} with default updater settings - review it before production use", file);
                return withConfigProbe(UpdateSettings.defaults(), sharedConfigPath, logger);
            }
        } catch (IOException e) {
            logger.warn("could not seed {}; updater runs on built-in defaults for this boot ({})", file, e.toString());
            return UpdateSettings.defaults();
        }

        final ConfigurationNode root;
        try {
            String raw = Files.readString(file);
            root = YamlConfigurationLoader.builder()
                    .defaultOptions(opts -> opts.implicitInitialization(false))
                    .buildAndLoadString(raw);
        } catch (Exception e) {
            // the file is corrupt YAML - report it, disable the updater, leave everything else alone
            logger.warn("{} is not valid YAML; updater is disabled for this boot", file);
            return UpdateSettings.disabled(List.of(FILE_NAME + ": not valid YAML (" + abbreviate(e.getMessage()) + ")"));
        }

        List<String> problems = new ArrayList<>();
        ConfigurationNode updates = child(root, "updates");

        boolean check = bool(updates, "check", true,
                "updates.check", problems);
        UpdateChannel channel = UpdateChannel.fromWire(text(updates, "channel", "release"));
        if (channel == null) {
            problems.add("updates.channel: must be 'release' or 'dev'");
        }
        UpdateConsent consent = UpdateConsent.fromWire(text(updates, "consent", "notify"));
        if (consent == null) {
            problems.add("updates.consent: must be 'notify' or 'auto-install'");
        }
        String token = text(updates, "license-token", "");
        List<String> suffixes = suffixes(updates, problems);

        if (!problems.isEmpty()) {
            logger.warn("{} rejected: {} problem(s) - updater is disabled for this boot", file, problems.size());
            return UpdateSettings.disabled(problems);
        }

        UpdateSettings settings = new UpdateSettings(check, channel, consent, token,
                suffixes, List.of());
        return withConfigProbe(settings, sharedConfigPath, logger);
    }

    /** Applies the config.yml {@code license.token} probe when the update.yml token is blank. */
    private static UpdateSettings withConfigProbe(UpdateSettings loaded,
                                                  Path sharedConfigPath,
                                                  Logger logger) {
        if (!loaded.licenseToken().isBlank() || sharedConfigPath == null) {
            return loaded;
        }
        String probed = probeConfigToken(sharedConfigPath, logger);
        if (probed == null || probed.isBlank()) {
            return loaded;
        }
        return new UpdateSettings(loaded.checkEnabled(), loaded.channel(), loaded.consent(),
                probed, loaded.redirectAllowSuffixes(), loaded.problems());
    }

    /** Lenient raw read of {@code license.token} from the shared config; any failure short of I/O reads as "absent". */
    private static String probeConfigToken(Path configPath, Logger logger) {
        try {
            if (!Files.exists(configPath)) {
                return null;
            }
            ConfigurationNode root = YamlConfigurationLoader.builder()
                    .defaultOptions(opts -> opts.implicitInitialization(false))
                    .buildAndLoadString(Files.readString(configPath));
            ConfigurationNode license = child(root, "license");
            if (license == null) {
                return null;
            }
            String token = license.node("token").getString();
            return token == null ? null : token.trim();
        } catch (Exception e) {
            // a config.yml this plugin cannot even parse is the shared loader's problem, not the updater's
            logger.debug("license.token probe skipped: {}", e.toString());
            return null;
        }
    }

    private static ConfigurationNode child(ConfigurationNode parent, String key) {
        if (parent == null) {
            return null;
        }
        ConfigurationNode node = parent.node(key);
        return node.empty() ? null : node;
    }

    private static boolean bool(ConfigurationNode parent, String key, boolean def,
                                String path, List<String> problems) {
        if (parent == null) {
            return def;
        }
        var node = parent.node(key);
        if (node.empty()) {
            return def;
        }
        // strict: a non-boolean scalar is a config problem, not a silent "false"
        Object raw = node.rawScalar();
        if (raw instanceof Boolean b) {
            return b;
        }
        problems.add(path + ": must be true or false");
        return def;
    }

    private static String text(ConfigurationNode parent, String key, String def) {
        if (parent == null) {
            return def;
        }
        var node = parent.node(key);
        if (node.empty()) {
            return def;
        }
        String value = node.getString();
        return value == null ? def : value.trim();
    }

    private static List<String> suffixes(ConfigurationNode updates, List<String> problems) {
        List<String> suffixes = new ArrayList<>();
        if (updates == null) {
            return UpdateSettings.DEFAULT_REDIRECT_SUFFIXES;
        }
        var node = updates.node("redirect-allow-suffixes");
        if (node.empty() || !node.isList()) {
            if (!node.empty()) {
                problems.add("updates.redirect-allow-suffixes: must be a list of host suffixes");
            }
            return UpdateSettings.DEFAULT_REDIRECT_SUFFIXES;
        }
        for (ConfigurationNode entry : node.childrenList()) {
            String value = entry.getString();
            if (value != null && HOST_SUFFIX.matcher(value.trim()).matches()) {
                suffixes.add(value.trim().toLowerCase(java.util.Locale.ROOT));
            } else {
                problems.add("updates.redirect-allow-suffixes: '" + value + "' is not a host suffix");
            }
        }
        return suffixes.isEmpty() ? UpdateSettings.DEFAULT_REDIRECT_SUFFIXES : suffixes;
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "no detail";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) + "..." : singleLine;
    }

    /** The seeded template; comments carry the operator docs, keep them honest. */
    public static String template() {
        return """
                # Continuum updater settings. This file is owned by the updater;
                # delete it and a fresh default is written on the next start.
                # Never commit a filled-in license token anywhere.
                updates:
                  # release | dev. The dev channel checks anonymous development builds.
                  channel: release
                  # Master switch for update checks (at enable + every 6h).
                  # The plugin runs fully without them.
                  check: true
                  # notify: log one line when an update exists; you run
                  #   /continuum update install when you want it.
                  # auto-install: download, verify and stage automatically;
                  #   the update still only applies at the next restart.
                  consent: notify
                  # ctm_live_... token for the release channel, from
                  # /continuum license redeem. Leave blank on dev.
                  # A license.token entry in config.yml is honored as a fallback.
                  license-token: ""
                  # Host suffixes a redirect may target when chasing the update
                  # artifact. https only; anything else is refused.
                  redirect-allow-suffixes:
                    - .r2.dev
                """;
    }
}
