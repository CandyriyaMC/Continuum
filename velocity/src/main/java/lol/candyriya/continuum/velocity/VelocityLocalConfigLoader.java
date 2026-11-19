package lol.candyriya.continuum.velocity;

import lol.candyriya.continuum.common.config.ConfigurationException;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Seeds and loads {@code velocity.yml}. Mirrors the behavior of the shared
 * config loader: missing file is a loud error (velocity should never run on an
 * absent config and guess), unknown keys are rejected, out-of-range values are
 * reported together, and valid files load even when values are odd but legal.
 */
public final class VelocityLocalConfigLoader {

    /** Classpath location of the bundled reference file (raw bytes, comments included). */
    public static final String BUNDLED_RESOURCE = "/velocity-default.yml";

    /** File name used inside the plugin data directory. */
    public static final String FILE_NAME = "velocity.yml";

    private static final Set<String> ROOT_KEYS = Set.of("velocity");
    private static final Set<String> VELOCITY_KEYS = Set.of("reconnect-debounce-seconds");

    private VelocityLocalConfigLoader() {
    }

    /** Raw bytes of the bundled reference config. */
    public static byte[] bundledDefault() {
        try (InputStream in = VelocityLocalConfigLoader.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled default " + BUNDLED_RESOURCE + " missing from classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + BUNDLED_RESOURCE, e);
        }
    }

    /**
     * Writes the bundled default at {@code path} when absent.
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
            try {
                Files.write(path, bundledDefault());
            } catch (FileAlreadyExistsException e) {
                // lost a seeding race against another plugin instance; not worth crashing over
                return false;
            }
            return true;
        } catch (IOException e) {
            throw new ConfigurationException("cannot write default proxy configuration to " + path, e);
        }
    }

    /** Loads and validates {@code path}. Throws {@link ConfigurationException} on any problem. */
    public static VelocityLocalConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigurationException(
                    List.of("missing proxy-local configuration: " + path + " (seed one first or delete it to get a fresh default)"));
        }
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .defaultOptions(opts -> opts.implicitInitialization(false))
                .path(path)
                .build();
        try {
            CommentedConfigurationNode root = loader.load();
            return finishLoad(root);
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        }
    }

    /** Loads and validates config from a YAML string. Intended for tests. */
    public static VelocityLocalConfig load(String yaml) {
        try {
            ConfigurationNode root = YamlConfigurationLoader.builder()
                    .defaultOptions(opts -> opts.implicitInitialization(false))
                    .buildAndLoadString(yaml);
            return finishLoad(root);
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        }
    }

    private static VelocityLocalConfig finishLoad(ConfigurationNode root) {
        List<String> problems = new ArrayList<>();
        for (Object key : root.childrenMap().keySet()) {
            String name = String.valueOf(key);
            if (!ROOT_KEYS.contains(name)) {
                problems.add("velocity.yml: unknown top-level key '" + name + "' (known: velocity)");
            }
        }
        long debounce = VelocityTuning.DEFAULT.reconnectDebounceSeconds();
        ConfigurationNode velocity = root.node("velocity");
        if (!velocity.virtual()) {
            if (!velocity.isMap()) {
                problems.add("velocity.yml: 'velocity' must be a section of keys");
            } else {
                for (Object key : velocity.childrenMap().keySet()) {
                    String name = String.valueOf(key);
                    if (!VELOCITY_KEYS.contains(name)) {
                        problems.add("velocity.yml: unknown key 'velocity." + name + "' (known: velocity.reconnect-debounce-seconds)");
                    }
                }
                ConfigurationNode debounceNode = velocity.node("reconnect-debounce-seconds");
                if (!debounceNode.virtual()) {
                    Object raw = debounceNode.raw();
                    if (!(raw instanceof Number)) {
                        problems.add("velocity.reconnect-debounce-seconds: must be an integer number of seconds");
                    } else {
                        debounce = ((Number) raw).longValue();
                        if (debounce < VelocityTuning.MIN_DEBOUNCE_SECONDS || debounce > VelocityTuning.MAX_DEBOUNCE_SECONDS) {
                            problems.add("velocity.reconnect-debounce-seconds: must be between "
                                    + VelocityTuning.MIN_DEBOUNCE_SECONDS + " and " + VelocityTuning.MAX_DEBOUNCE_SECONDS
                                    + " (got " + debounce + ")");
                        }
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(problems);
        }
        return new VelocityLocalConfig(new VelocityTuning(debounce));
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
        return "velocity.yml " + location + ": " + e.rawMessage();
    }
}
