package lol.candyriya.continuum.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    private static final String MINIMAL = """
            network:
              id: test-network
              server-id: test-1
              role: backend
            database:
              type: mysql
              mysql:
                host: localhost
                port: 3306
                database: continuum
                username: continuum
                password: "sup3r-s3cret-value"
                pool:
                  maximum-pool-size: 10
                  minimum-idle: 2
                  connection-timeout: 5s
                  max-lifetime: 30m
            messaging:
              rabbitmq:
                enabled: true
                uri: "amqp://user:pass@broker:5672/"
                exchange: continuum.events
                retry:
                  max-attempts: 5
                  initial-backoff: 200ms
                  max-backoff: 10s
              plugin-message:
                enabled: true
                channel: continuum:messaging
                max-payload-bytes: 32768
            """;

    @Test
    void bundledDefaultConfigIsValid() {
        // the shipped reference config must always load clean — it is the first thing operators touch
        ContinuumConfig config = ConfigLoader.load(new String(ConfigLoader.bundledDefault(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("main-network", config.network().id());
        assertEquals("survival-1", config.network().serverId());
        assertEquals(ServerRole.BACKEND, config.network().role());
        assertEquals(DatabaseType.MYSQL, config.database().type());
        assertEquals(3306, config.database().mysql().port());
        assertEquals(Duration.ofSeconds(60), config.sync().autosave().interval());
        assertEquals(13, config.sync().modulesWithDefaults().size());
        assertTrue(config.sync().modulesWithDefaults().values().stream().allMatch(Boolean::valueOf));
        assertEquals("en_US", config.i18n().fallbackLocale());
        assertFalse(config.serialization().gzip());
        assertEquals(8 * 1024 * 1024, config.serialization().maxSnapshotBytes());
    }

    @Test
    void minimalConfigLoadsWithDefaultsFilled() {
        ContinuumConfig config = ConfigLoader.load(MINIMAL);
        assertTrue(config.sync().autosave().enabled());
        assertEquals(Duration.ofSeconds(60), config.sync().autosave().interval());
        assertEquals(List.of(), config.sync().recovery().restoreOrder());
        assertEquals(32768, config.messaging().pluginMessage().maxPayloadBytes());
        assertEquals(5, config.messaging().rabbitmq().retry().maxAttempts());
        assertFalse(config.debug());
    }

    @Test
    void mongodbConfigLoadsAndValidates() {
        String mongo = """
                network:
                  id: test-network
                  server-id: test-1
                  role: backend
                database:
                  type: mongodb
                  mongodb:
                    uri: "mongodb://user:pass@mongo:27017/continuum?authSource=admin"
                    database: continuum
                    pool:
                      max-size: 50
                      min-size: 2
                      max-wait-time: 5s
                messaging:
                  rabbitmq:
                    enabled: true
                    uri: "amqp://user:pass@broker:5672/"
                    exchange: continuum.events
                    retry:
                      max-attempts: 3
                      initial-backoff: 100ms
                      max-backoff: 5s
                """;
        ContinuumConfig config = ConfigLoader.load(mongo);
        assertEquals(DatabaseType.MONGODB, config.database().type());
        assertEquals(50, config.database().mongodb().pool().maxSize());
        assertEquals(Duration.ofSeconds(5), config.database().mongodb().pool().maxWaitTime());
        assertEquals("continuum", config.database().mongodb().database());
    }

    @Test
    void outOfRangePortIsRejectedWithKeyPath() {
        String yaml = MINIMAL.replace("port: 3306", "port: 99999");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("database.mysql.port") && v.contains("65535")),
                "expected a port violation, got: " + e.violations());
    }

    @Test
    void blankNetworkIdIsRejected() {
        String yaml = MINIMAL.replace("id: test-network", "id: \"\"");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("network.id")));
    }

    @Test
    void serverIdWithSpacesIsRejected() {
        String yaml = MINIMAL.replace("server-id: test-1", "server-id: \"not a server id\"");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("network.server-id")));
    }

    @Test
    void unknownDatabaseTypeIsRejected() {
        String yaml = MINIMAL.replace("type: mysql", "type: oracle");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertFalse(e.violations().isEmpty());
    }

    @Test
    void mongodbWithoutUriIsRejected() {
        String yaml = MINIMAL.replace("type: mysql", "type: mongodb");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("database.mongodb.uri")));
    }

    @Test
    void mongodbUriWithBadSchemeIsRejected() {
        String yaml = """
                network:
                  id: test-network
                  server-id: test-1
                  role: backend
                database:
                  type: mongodb
                  mongodb:
                    uri: "http://mongo:27017/"
                    database: continuum
                    pool:
                      max-size: 10
                      min-size: 1
                      max-wait-time: 5s
                messaging:
                  plugin-message:
                    enabled: true
                    channel: continuum:messaging
                    max-payload-bytes: 32768
                """;
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("mongodb://")));
    }

    @Test
    void rabbitmqEnabledWithBlankUriIsRejected() {
        String yaml = MINIMAL.replace("uri: \"amqp://user:pass@broker:5672/\"", "uri: \"\"");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("messaging.rabbitmq.uri")));
    }

    @Test
    void invalidExchangeNameIsRejected() {
        String yaml = MINIMAL.replace("exchange: continuum.events", "exchange: \"bad exchange!\"");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("messaging.rabbitmq.exchange")));
    }

    @Test
    void channelWithoutNamespaceIsRejected() {
        String yaml = MINIMAL.replace("channel: continuum:messaging", "channel: continuummessaging");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("messaging.plugin-message.channel")));
    }

    @Test
    void bothTransportsDisabledIsRejected() {
        String yaml = """
                network:
                  id: test-network
                  server-id: test-1
                  role: backend
                database:
                  type: mysql
                  mysql:
                    host: localhost
                    port: 3306
                    database: continuum
                    username: continuum
                    password: ""
                    pool:
                      maximum-pool-size: 10
                      minimum-idle: 2
                      connection-timeout: 5s
                      max-lifetime: 30m
                messaging:
                  rabbitmq:
                    enabled: false
                    uri: ""
                    exchange: continuum.events
                  plugin-message:
                    enabled: false
                    channel: continuum:messaging
                    max-payload-bytes: 32768
                """;
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("messaging:")),
                "expected the both-disabled violation, got: " + e.violations());
    }

    @Test
    void unknownModuleIdInModulesMapIsRejected() {
        String yaml = MINIMAL + "sync:\n  modules:\n    inventry: true\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("sync.modules.inventry")));
    }

    @Test
    void restoreOrderWithUnknownIdIsRejected() {
        String yaml = MINIMAL + "sync:\n  recovery:\n    restore-order:\n      - inventory\n      - bogus-module\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("bogus-module")));
    }

    @Test
    void restoreOrderWithDuplicateIsRejected() {
        String yaml = MINIMAL + "sync:\n  recovery:\n    restore-order:\n      - inventory\n      - inventory\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("duplicate")));
    }

    @Test
    void maxSnapshotBytesOutOfRangeIsRejected() {
        String yaml = MINIMAL + "serialization:\n  max-snapshot-bytes: 100\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("serialization.max-snapshot-bytes")));
    }

    @Test
    void invalidDurationStringIsRejected() {
        String yaml = MINIMAL.replace("connection-timeout: 5s", "connection-timeout: soon");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertFalse(e.violations().isEmpty());
    }

    @Test
    void durationFormatsRoundTrip() {
        String yaml = MINIMAL.replace("connection-timeout: 5s", "connection-timeout: PT30S")
                .replace("max-lifetime: 30m", "max-lifetime: 3600");
        ContinuumConfig config = ConfigLoader.load(yaml);
        assertEquals(Duration.ofSeconds(30), config.database().mysql().pool().connectionTimeout());
        assertEquals(Duration.ofSeconds(3600), config.database().mysql().pool().maxLifetime());
    }

    @Test
    void durationMillisAndHoursParse() {
        String yaml = MINIMAL.replace("connection-timeout: 5s", "connection-timeout: 250ms")
                .replace("max-lifetime: 30m", "max-lifetime: 1h");
        ContinuumConfig config = ConfigLoader.load(yaml);
        assertEquals(Duration.ofMillis(250), config.database().mysql().pool().connectionTimeout());
        assertEquals(Duration.ofHours(1), config.database().mysql().pool().maxLifetime());
    }

    @Test
    void autosaveIntervalBelowFloorIsRejected() {
        String yaml = MINIMAL + "sync:\n  autosave:\n    interval: 2s\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("sync.autosave.interval")));
    }

    @Test
    void allViolationsAreReportedAtOnce() {
        String yaml = MINIMAL
                .replace("port: 3306", "port: 99999")
                .replace("id: test-network", "id: \"\"")
                + "serialization:\n  max-snapshot-bytes: 10\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        // at minimum: port, network.id, max-snapshot-bytes — reported together, not one per restart
        assertTrue(e.violations().size() >= 3, "expected >= 3 violations, got: " + e.violations());
        assertTrue(e.getMessage().contains("database.mysql.port"));
        assertTrue(e.getMessage().contains("network.id"));
        assertTrue(e.getMessage().contains("max-snapshot-bytes"));
    }

    @Test
    void secretsNeverAppearInViolations() {
        String yaml = MINIMAL.replace("port: 3306", "port: 99999"); // force a violation elsewhere
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        for (String violation : e.violations()) {
            assertFalse(violation.contains("sup3r-s3cret-value"), "violation leaks the password: " + violation);
        }
        assertFalse(e.getMessage().contains("sup3r-s3cret-value"));
    }

    @Test
    void secretsAreMaskedInToString() {
        ContinuumConfig config = ConfigLoader.load(MINIMAL);
        assertFalse(config.database().mysql().toString().contains("sup3r-s3cret-value"));
        assertFalse(config.messaging().rabbitmq().toString().contains("amqp://user:pass@broker:5672/"));
        assertTrue(config.database().mysql().toString().contains("<masked>"));
        assertTrue(config.messaging().rabbitmq().toString().contains("<masked>"));
    }

    @Test
    void seedIfMissingWritesDefaultThenStops(@TempDir Path dir) {
        Path target = dir.resolve("config.yml");
        assertTrue(ConfigLoader.seedIfMissing(target));
        assertTrue(Files.exists(target));
        assertFalse(ConfigLoader.seedIfMissing(target), "must not overwrite an existing config");
    }

    @Test
    void missingFileIsAnErrorNotASilentDefault(@TempDir Path dir) {
        Path target = dir.resolve("config.yml");
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(target));
        assertTrue(e.violations().get(0).contains("missing configuration file"));
    }

    @Test
    void unknownModuleIdsReportKnownList() {
        String yaml = MINIMAL + "sync:\n  modules:\n    bogus: true\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.contains("inventory")),
                "violation should name known module ids to help the operator");
    }

    @Test
    void modulesDefaultToAllKnownAndTrueWhenAbsent() {
        ContinuumConfig config = ConfigLoader.load(MINIMAL);
        var defaults = config.sync().modulesWithDefaults();
        assertEquals(13, defaults.size());
        assertEquals(KnownModules.ALL.stream().collect(java.util.stream.Collectors.toSet()), defaults.keySet());
        assertTrue(defaults.values().stream().allMatch(Boolean::valueOf));
    }

    @Test
    void validRestoreOrderIsAccepted() {
        String yaml = MINIMAL + "sync:\n  recovery:\n    restore-order:\n      - experience\n      - inventory\n";
        ContinuumConfig config = ConfigLoader.load(yaml);
        assertEquals(List.of("experience", "inventory"), config.sync().recovery().restoreOrder());
    }

    @Test
    void i18nLocaleTagsAreValidated() {
        String yaml = MINIMAL + "i18n:\n  default-locale: english\n";
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> ConfigLoader.load(yaml));
        assertTrue(e.violations().stream().anyMatch(v -> v.startsWith("i18n.default-locale")));
    }
}

