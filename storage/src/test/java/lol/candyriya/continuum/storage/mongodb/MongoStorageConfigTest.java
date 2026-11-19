package lol.candyriya.continuum.storage.mongodb;

import lol.candyriya.continuum.common.config.MongoPool;
import lol.candyriya.continuum.common.config.MongoSettings;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit coverage for {@link MongoStorageConfig}: mapping and validation, no database involved. */
class MongoStorageConfigTest {

    private static MongoSettings settings(int maxSize, int minSize, Duration maxWaitTime) {
        return new MongoSettings("mongodb://localhost:27017", "continuum",
                new MongoPool(maxSize, minSize, maxWaitTime));
    }

    @Test
    void fromMapsPoolAndFixedClientSettings() {
        MongoStorageConfig config = MongoStorageConfig.from(settings(24, 4, Duration.ofSeconds(3)));

        assertEquals("mongodb://localhost:27017", config.uri());
        assertEquals("continuum", config.database());
        assertEquals(4, config.minPoolSize());
        assertEquals(24, config.maxPoolSize());
        assertEquals(Duration.ofSeconds(3), config.maxWaitTime());
        assertEquals(MongoStorageConfig.CONNECT_TIMEOUT, config.connectTimeout());
        assertEquals(MongoStorageConfig.SERVER_SELECTION_TIMEOUT, config.serverSelectionTimeout());
        assertEquals(MongoStorageConfig.APPLICATION_NAME, config.applicationName());
        assertTrue(config.retryReads());
        assertTrue(config.retryWrites());
        // the builder must not blow up on the mapped settings
        config.toClientSettings();
    }

    @Test
    void nullPoolIsRejected() {
        MongoSettings settings = new MongoSettings("mongodb://localhost:27017", "continuum", null);
        assertThrows(IllegalArgumentException.class, () -> MongoStorageConfig.from(settings));
    }

    @Test
    void blankUriIsRejected() {
        MongoSettings settings = new MongoSettings(" ", "continuum",
                new MongoPool(8, 1, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> MongoStorageConfig.from(settings));
    }

    @Test
    void malformedUriIsRejectedWithoutLeakingItIntoTheMessage() {
        String secretUri = "postgres://user:s3cr3t-password@localhost:5432/continuum";
        MongoSettings settings = new MongoSettings(secretUri, "continuum",
                new MongoPool(8, 1, Duration.ofSeconds(5)));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> MongoStorageConfig.from(settings));
        assertTrue(thrown.getMessage().contains("connection string"),
                "the message should point at the URI being invalid, got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().indexOf("s3cr3t-password") < 0,
                "the message must not echo the connection string");
        assertTrue(thrown.getCause() == null || thrown.getCause().getMessage() == null
                        || thrown.getCause().getMessage().indexOf("s3cr3t-password") < 0,
                "the cause must not echo the connection string either");
    }

    @Test
    void databaseNameValidation() {
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "bad/name", 1, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "bad.name", 1, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "", 1, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "x".repeat(64), 1, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
    }

    @Test
    void poolBoundsAreValidated() {
        // min > max
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "continuum", 16, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        // maxPoolSize below 1
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "continuum", 0, 0, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        // negative min
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "continuum", -1, 8, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        // maxWaitTime out of range
        assertThrows(IllegalArgumentException.class, () -> new MongoStorageConfig(
                "mongodb://localhost:27017", "continuum", 0, 8, Duration.ofMillis(100),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME));
        // min == max is fine (fixed pool)
        new MongoStorageConfig("mongodb://localhost:27017", "continuum", 4, 4, Duration.ofSeconds(5),
                MongoStorageConfig.CONNECT_TIMEOUT, MongoStorageConfig.SERVER_SELECTION_TIMEOUT,
                true, true, MongoStorageConfig.APPLICATION_NAME).toClientSettings();
    }
}
