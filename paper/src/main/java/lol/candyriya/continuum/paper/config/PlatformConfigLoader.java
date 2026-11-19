package lol.candyriya.continuum.paper.config;

import lol.candyriya.continuum.common.config.ConfigurationException;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and validates {@code platform.yml}, the backend-node settings.
 *
 * <p>Mirrors the common configuration loader on purpose: same file contract
 * (bundled defaults are seeded when absent, absent file is an error), same
 * failure shape (one {@link ConfigurationException} naming every violation
 * found), same secret hygiene (violations name paths and rules, never values).</p>
 */
public final class PlatformConfigLoader {

    /** Classpath location of the bundled reference platform config (raw bytes, comments included). */
    public static final String DEFAULT_RESOURCE = "/platform.yml";

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformConfigLoader.class);

    private PlatformConfigLoader() {
    }

    /** Raw bytes of the bundled reference platform config. Useful to seed installs or to restore a broken file. */
    public static byte[] bundledDefault() {
        try (InputStream in = PlatformConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled default platform config missing from classpath: " + DEFAULT_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + DEFAULT_RESOURCE, e);
        }
    }

    /**
     * Writes the bundled default platform config at {@code path} when absent.
     *
     * @return true when a file was written, false when one already existed
     */
    public static boolean seedIfMissing(Path path) {
        if (Files.exists(path)) {
            return false;
        }
        try {
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            Files.write(path, bundledDefault());
            return true;
        } catch (IOException e) {
            throw new ConfigurationException("cannot write default platform configuration to " + path, e);
        }
    }

    /** Loads and validates {@code path}. Throws {@link ConfigurationException} on any problem. */
    public static PlatformConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigurationException(
                    List.of("missing platform configuration file: " + path + " (seed one with PlatformConfigLoader.seedIfMissing first)"));
        }
        YamlConfigurationLoader loader = baseBuilder().path(path).build();
        try {
            CommentedConfigurationNode root = loader.load();
            return finishLoad(root, path.toString());
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        }
    }

    private static YamlConfigurationLoader.Builder baseBuilder() {
        return YamlConfigurationLoader.builder()
                // absent sections must surface as null so our defaults fill in;
                // otherwise the mapper hands us an all-null skeleton for every missing record
                .defaultOptions(opts -> opts.implicitInitialization(false));
    }

    private static PlatformConfig finishLoad(CommentedConfigurationNode root, String source) {
        PlatformConfig config;
        try {
            config = root.get(PlatformConfig.class);
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        } catch (RuntimeException e) {
            // record compact constructors throw on malformed values — give the operator the message, not the stack
            throw new ConfigurationException("platform configuration mapping failed: " + e.getMessage(), e);
        }
        List<String> violations = validate(config);
        if (!violations.isEmpty()) {
            LOGGER.warn("rejected platform configuration from {}: {} violation(s)", source, violations.size());
            throw new ConfigurationException(violations);
        }
        return config;
    }

    private static String describe(ConfigurateException e) {
        var path = e.path();
        String location;
        if (path == null) {
            location = "<root>";
        } else {
            var sb = new StringBuilder();
            for (Object element : path) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(element);
            }
            location = sb.isEmpty() ? "<root>" : sb.toString();
        }
        return location + ": " + e.rawMessage();
    }

    /**
     * Semantic validation. Returns every violation found; empty means valid.
     */
    public static List<String> validate(PlatformConfig config) {
        List<String> problems = new ArrayList<>();
        config.gate().validate(problems);
        config.spawnPolicy().validate(problems);
        return List.copyOf(problems);
    }
}
