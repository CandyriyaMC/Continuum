package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.common.config.MongoPool;
import lol.candyriya.continuum.common.config.MongoSettings;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB client configuration for one storage instance.
 *
 * <p>Maps common's {@link MongoSettings} + {@link MongoPool} onto driver
 * settings. One {@code MongoClient} is built per instance from this record,
 * never shared across configs. The database type in {@code database.type}
 * selects the Mongo path at factory time; this record only describes the
 * Mongo side.</p>
 *
 * <p>Verified against MongoDB 8.0 / Atlas M0: the pool caps matter because
 * M0 allows 500 connections total per cluster, so {@code maxPoolSize}
 * (default 16-32 territory) must be multiplied by the number of backends in
 * the network, not set to the driver's 100 default. The verification ran
 * against driver 5.14 — the socket/pool/cluster builders take
 * {@code (long, TimeUnit)} there; there is no {@code applyToTimeoutSettings}
 * and no write-timeout knob.</p>
 *
 * <p>Fixed values deliberately not exposed as knobs: connect timeout 5s,
 * server selection timeout 10s ({@code serverSelectionTimeout} lives on the
 * cluster settings, not the socket settings), {@code retryReads} and
 * {@code retryWrites} forced on, {@code applicationName} forced to
 * {@code "continuum"} so operators can spot the app in server logs and
 * Atlas metrics. {@code readTimeout} is left at the driver default
 * (unlimited) — snapshot payloads are KBs, and a read deadline is a tuning
 * decision nobody has measured yet; adding one now would be a guess dressed
 * up as a setting.</p>
 *
 * <p>The URI is never logged, never included in exception messages, and never
 * echoed back to the caller: it may embed credentials.</p>
 */
public record MongoStorageConfig(String uri,
                                 String database,
                                 int minPoolSize,
                                 int maxPoolSize,
                                 Duration maxWaitTime,
                                 Duration connectTimeout,
                                 Duration serverSelectionTimeout,
                                 boolean retryReads,
                                 boolean retryWrites,
                                 String applicationName) {

    /** Application name forced on every client built from this config. */
    public static final String APPLICATION_NAME = "continuum";
    /** Socket connect timeout: long enough to survive a loaded mongod, short enough to fail fast. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Server selection timeout: bounds how long an operation waits for a usable server. */
    public static final Duration SERVER_SELECTION_TIMEOUT = Duration.ofSeconds(10);

    public MongoStorageConfig {
        Objects.requireNonNull(uri, "uri must not be null");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("mongodb uri must not be blank");
        }
        validateDatabaseName(database);
        if (minPoolSize < 0) {
            throw new IllegalArgumentException("minPoolSize must be >= 0, got " + minPoolSize);
        }
        if (maxPoolSize < 1) {
            throw new IllegalArgumentException("maxPoolSize must be >= 1, got " + maxPoolSize);
        }
        if (minPoolSize > maxPoolSize) {
            throw new IllegalArgumentException("minPoolSize (" + minPoolSize + ") must not exceed maxPoolSize (" + maxPoolSize + ")");
        }
        requireDuration("maxWaitTime", maxWaitTime, Duration.ofMillis(250), Duration.ofSeconds(30));
        requireDuration("connectTimeout", connectTimeout, Duration.ofMillis(250), Duration.ofSeconds(30));
        requireDuration("serverSelectionTimeout", serverSelectionTimeout, Duration.ofMillis(250), Duration.ofSeconds(60));
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must not be blank");
        }
        if (applicationName.length() > 128) {
            // the driver rejects longer application names with IllegalArgumentException anyway; fail with a better message
            throw new IllegalArgumentException("applicationName must be at most 128 characters, got " + applicationName.length());
        }
        // fail fast on a malformed connection string; the cause's message may echo the URI (it can embed
        // credentials), so it stays out of both the message and the cause chain
        try {
            new ConnectionString(uri);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("uri is not a valid MongoDB connection string; check database.mongodb.uri in the configuration");
        }
    }

    /**
     * Maps common's validated configuration onto driver settings.
     *
     * @param settings the {@code database.mongodb} block; must not be null
     * @throws IllegalArgumentException when the URI or the pool tuning make no sense;
     *         messages never echo the URI
     */
    public static MongoStorageConfig from(MongoSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        MongoPool pool = settings.pool();
        if (pool == null) {
            throw new IllegalArgumentException("mongodb pool settings must not be null; database.mongodb.pool is required");
        }
        return new MongoStorageConfig(
                settings.uri(),
                settings.database(),
                pool.minSize(),
                pool.maxSize(),
                requireWaitTime(pool.maxWaitTime()),
                CONNECT_TIMEOUT,
                SERVER_SELECTION_TIMEOUT,
                true,
                true,
                APPLICATION_NAME);
    }

    /** Builds the client settings; the connection string is applied first and explicit values win over URI parameters. */
    public MongoClientSettings toClientSettings() {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applicationName(applicationName)
                .retryReads(retryReads)
                .retryWrites(retryWrites)
                .applyToSocketSettings(builder -> builder.connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(builder -> builder
                        .minSize(minPoolSize)
                        .maxSize(maxPoolSize)
                        .maxWaitTime(maxWaitTime.toMillis(), TimeUnit.MILLISECONDS))
                .applyToClusterSettings(builder -> builder
                        .serverSelectionTimeout(serverSelectionTimeout.toMillis(), TimeUnit.MILLISECONDS))
                .build();
    }

    private static Duration requireWaitTime(Duration maxWaitTime) {
        if (maxWaitTime == null) {
            throw new IllegalArgumentException("mongodb pool maxWaitTime must not be null; database.mongodb.pool.maxWaitTime is required");
        }
        return maxWaitTime;
    }

    private static void requireDuration(String name, Duration value, Duration min, Duration max) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ", got " + value);
        }
    }

    private static void validateDatabaseName(String database) {
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("mongodb database name must not be blank");
        }
        if (database.length() > 63) {
            throw new IllegalArgumentException("mongodb database name must be at most 63 characters, got " + database.length());
        }
        // names are used in commands and logs, so keep them boring: no path separators, quotes, or wildcards
        for (int i = 0; i < database.length(); i++) {
            char c = database.charAt(i);
            if (c == '/' || c == '\\' || c == '.' || c == '"' || c == '$' || c == '*' || c == '<' || c == '>' || c == ':' || c == '|' || c == '?' || c == ' ') {
                throw new IllegalArgumentException("mongodb database name contains an illegal character: " + database);
            }
        }
    }
}
