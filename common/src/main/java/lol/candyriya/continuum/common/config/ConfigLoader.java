package lol.candyriya.continuum.common.config;

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
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Loads and validates {@code config.yml}.
 *
 * <p>Two failure tiers, both reported as {@link ConfigurationException}:
 * type mapping (wrong shapes, wrong types — reported with the YAML path) and
 * semantic validation (out-of-range values, cross-field rules, unknown module
 * ids), reported with every violation found, never one restart per error.</p>
 *
 * <p>Secret values (passwords, URIs with embedded credentials) are never
 * echoed into exceptions or logs — violations name paths and rules only.</p>
 *
 * <p>Missing file: platforms should call {@link #seedIfMissing(Path)} first to
 * write the bundled reference config, then {@link #load(Path)}; an absent file
 * is an error, not a silent default, so misconfigured deployments fail loudly.</p>
 */
public final class ConfigLoader {

    /** Classpath location of the bundled reference config (raw bytes, comments included). */
    public static final String DEFAULT_RESOURCE = "/config.yml";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    private static final Pattern DNSISH = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
    private static final Pattern EXCHANGE = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern CHANNEL = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern LOCALE_TAG = Pattern.compile("^[a-z]{2,3}_[A-Z]{2}$");

    private ConfigLoader() {
    }

    /** Raw bytes of the bundled reference config. Useful to seed installs or to restore a broken file. */
    public static byte[] bundledDefault() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled default config missing from classpath: " + DEFAULT_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + DEFAULT_RESOURCE, e);
        }
    }

    /**
     * Writes the bundled default config at {@code path} when absent.
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
            throw new ConfigurationException("cannot write default configuration to " + path, e);
        }
    }

    /** Loads and validates {@code path}. Throws {@link ConfigurationException} on any problem. */
    public static ContinuumConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigurationException(
                    List.of("missing configuration file: " + path + " (seed one with ConfigLoader.seedIfMissing first)"));
        }
        YamlConfigurationLoader loader = baseBuilder()
                .path(path)
                .build();
        try {
            CommentedConfigurationNode root = loader.load();
            return finishLoad(root, path.toString());
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        }
    }

    /** Loads and validates config from a YAML string. Intended for tests and reload pipelines. */
    public static ContinuumConfig load(String yaml) {
        try {
            org.spongepowered.configurate.ConfigurationNode root = baseBuilder().buildAndLoadString(yaml);
            return finishLoad(root, "<inline>");
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        }
    }

    private static YamlConfigurationLoader.Builder baseBuilder() {
        return YamlConfigurationLoader.builder()
                // absent sections must surface as null so our defaults fill in;
                // otherwise the mapper hands us an all-null skeleton for every missing record
                .defaultOptions(opts -> opts.implicitInitialization(false).serializers(
                        serializers -> serializers.register(new DurationTypeSerializer())));
    }

    private static ContinuumConfig finishLoad(org.spongepowered.configurate.ConfigurationNode root, String source) {
        ContinuumConfig config;
        try {
            config = root.get(ContinuumConfig.class);
        } catch (ConfigurateException e) {
            throw new ConfigurationException(describe(e), e);
        } catch (RuntimeException e) {
            // record compact constructors throw IAE on malformed values — give the operator the message, not the stack
            throw new ConfigurationException("configuration mapping failed: " + e.getMessage(), e);
        }
        List<String> violations = validate(config);
        if (!violations.isEmpty()) {
            LOGGER.warn("rejected configuration from {}: {} violation(s)", source, violations.size());
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
     * Rules are intentionally insensitive to which engine/transport is
     * "active" beyond what the rules call out — disabled sections are still
     * shape-checked, they are just not required to be filled in.
     */
    public static List<String> validate(ContinuumConfig config) {
        List<String> problems = new ArrayList<>();

        // network identity
        checkName("network.id", config.network().id(), problems);
        checkName("network.server-id", config.network().serverId(), problems);

        // database
        DatabaseConfig db = config.database();
        if (db.type() == null) {
            problems.add("database.type: must be one of mysql, mariadb, mongodb");
        } else if (db.type() == DatabaseType.MONGODB) {
            validateMongo(db.mongodb(), problems);
        } else {
            validateMysql(db.mysql(), problems);
        }

        // messaging
        MessagingConfig messaging = config.messaging();
        RabbitMqConfig mq = messaging.rabbitmq();
        if (mq.enabled()) {
            if (mq.uri() == null || mq.uri().isBlank()) {
                problems.add("messaging.rabbitmq.uri: must not be blank when the broker is enabled");
            } else if (!mq.uri().startsWith("amqp://") && !mq.uri().startsWith("amqps://")) {
                problems.add("messaging.rabbitmq.uri: must start with amqp:// or amqps://");
            }
        }
        if (mq.exchange() == null || !EXCHANGE.matcher(mq.exchange()).matches()) {
            problems.add("messaging.rabbitmq.exchange: must be 1-128 chars of [A-Za-z0-9._-]");
        }
        validateRetry(mq.retry(), problems);

        PluginMessageConfig pm = messaging.pluginMessage();
        if (pm.channel() == null || !CHANNEL.matcher(pm.channel()).matches()) {
            problems.add("messaging.plugin-message.channel: must be a lowercase namespaced key like 'continuum:messaging'");
        }
        if (pm.maxPayloadBytes() < 1_024 || pm.maxPayloadBytes() > 65_536) {
            problems.add("messaging.plugin-message.max-payload-bytes: must be between 1024 and 65536");
        }
        if (!mq.enabled() && !pm.enabled()) {
            problems.add("messaging: both transports are disabled; enable at least one of messaging.rabbitmq.enabled or messaging.plugin-message.enabled");
        }

        // sync
        SyncConfig sync = config.sync();
        if (sync.autosave().interval() == null || sync.autosave().interval().toSeconds() < 5 || sync.autosave().interval().toHours() > 24) {
            problems.add("sync.autosave.interval: must be between 5s and 24h");
        }
        if (sync.recovery().interval() == null || sync.recovery().interval().toSeconds() < 5 || sync.recovery().interval().toHours() > 24) {
            problems.add("sync.recovery.interval: must be between 5s and 24h");
        }
        for (String moduleId : sync.modules().keySet()) {
            if (!KnownModules.isKnown(moduleId)) {
                problems.add("sync.modules." + moduleId + ": unknown module id (known: " + String.join(", ", KnownModules.ALL) + ")");
            }
        }
        List<String> restoreOrder = sync.recovery().restoreOrder();
        var seen = new HashSet<String>();
        for (String moduleId : restoreOrder) {
            if (!KnownModules.isKnown(moduleId)) {
                problems.add("sync.recovery.restore-order: unknown module id '" + moduleId + "'");
            } else if (!seen.add(moduleId)) {
                problems.add("sync.recovery.restore-order: duplicate module id '" + moduleId + "'");
            }
        }

        // i18n
        if (!isLocaleTag(config.i18n().defaultLocale())) {
            problems.add("i18n.default-locale: must look like 'en_US' (language_COUNTRY)");
        }
        if (!isLocaleTag(config.i18n().fallbackLocale())) {
            problems.add("i18n.fallback-locale: must look like 'en_US' (language_COUNTRY)");
        }

        // serialization
        int maxSnapshot = config.serialization().maxSnapshotBytes();
        if (maxSnapshot < 64 * 1_024 || maxSnapshot > 64 * 1_024 * 1_024) {
            problems.add("serialization.max-snapshot-bytes: must be between 65536 and 67108864");
        }

        return List.copyOf(problems);
    }

    private static void validateMysql(MysqlSettings mysql, List<String> problems) {
        if (mysql.host() == null || mysql.host().isBlank()) {
            problems.add("database.mysql.host: must not be blank when type is mysql/mariadb");
        }
        if (mysql.port() < 1 || mysql.port() > 65_535) {
            problems.add("database.mysql.port: must be between 1 and 65535");
        }
        if (mysql.database() == null || mysql.database().isBlank()) {
            problems.add("database.mysql.database: must not be blank");
        }
        if (mysql.username() == null || mysql.username().isBlank()) {
            problems.add("database.mysql.username: must not be blank");
        }
        if (mysql.password() != null && mysql.password().length() > 1_024) {
            problems.add("database.mysql.password: must be at most 1024 characters");
        }
        MysqlPool pool = mysql.pool();
        if (pool == null) {
            problems.add("database.mysql.pool: must not be missing");
            return;
        }
        if (pool.maximumPoolSize() < 1 || pool.maximumPoolSize() > 256) {
            problems.add("database.mysql.pool.maximum-pool-size: must be between 1 and 256");
        }
        if (pool.minimumIdle() < 0 || pool.minimumIdle() > pool.maximumPoolSize()) {
            problems.add("database.mysql.pool.minimum-idle: must be between 0 and maximum-pool-size");
        }
        if (pool.connectionTimeout() == null || pool.connectionTimeout().toMillis() < 100 || pool.connectionTimeout().toSeconds() > 60) {
            problems.add("database.mysql.pool.connection-timeout: must be between 100ms and 60s");
        }
        if (pool.maxLifetime() == null || pool.maxLifetime().toSeconds() < 1 || pool.maxLifetime().toMinutes() > 60) {
            problems.add("database.mysql.pool.max-lifetime: must be between 1s and 60m");
        }
    }

    private static void validateMongo(MongoSettings mongo, List<String> problems) {
        if (mongo.uri() == null || mongo.uri().isBlank()) {
            problems.add("database.mongodb.uri: must not be blank when type is mongodb");
        } else if (!mongo.uri().startsWith("mongodb://") && !mongo.uri().startsWith("mongodb+srv://")) {
            problems.add("database.mongodb.uri: must start with mongodb:// or mongodb+srv://");
        }
        if (mongo.database() == null || mongo.database().isBlank()) {
            problems.add("database.mongodb.database: must not be blank");
        }
        MongoPool pool = mongo.pool();
        if (pool == null) {
            problems.add("database.mongodb.pool: must not be missing");
            return;
        }
        if (pool.maxSize() < 1 || pool.maxSize() > 512) {
            problems.add("database.mongodb.pool.max-size: must be between 1 and 512");
        }
        if (pool.minSize() < 0 || pool.minSize() > pool.maxSize()) {
            problems.add("database.mongodb.pool.min-size: must be between 0 and max-size");
        }
        if (pool.maxWaitTime() == null || pool.maxWaitTime().toMillis() < 100 || pool.maxWaitTime().toSeconds() > 60) {
            problems.add("database.mongodb.pool.max-wait-time: must be between 100ms and 60s");
        }
    }

    private static void validateRetry(RabbitMqRetry retry, List<String> problems) {
        if (retry == null) {
            problems.add("messaging.rabbitmq.retry: must not be missing");
            return;
        }
        if (retry.maxAttempts() < 0 || retry.maxAttempts() > 16) {
            problems.add("messaging.rabbitmq.retry.max-attempts: must be between 0 and 16");
        }
        if (retry.initialBackoff() == null || retry.initialBackoff().toMillis() < 10 || retry.initialBackoff().toSeconds() > 60) {
            problems.add("messaging.rabbitmq.retry.initial-backoff: must be between 10ms and 60s");
        }
        if (retry.maxBackoff() == null || retry.maxBackoff().toSeconds() > 600) {
            problems.add("messaging.rabbitmq.retry.max-backoff: must be at most 600s");
        } else if (retry.initialBackoff() != null && retry.maxBackoff().compareTo(retry.initialBackoff()) < 0) {
            problems.add("messaging.rabbitmq.retry.max-backoff: must be >= initial-backoff");
        }
    }

    private static void checkName(String key, String value, List<String> problems) {
        if (value == null || !DNSISH.matcher(value).matches()) {
            problems.add(key + ": must be 1-63 chars, lowercase dns-ish ([a-z0-9][a-z0-9._-]*)");
        }
    }

    private static boolean isLocaleTag(String value) {
        return value != null && LOCALE_TAG.matcher(value.trim()).matches();
    }
}
