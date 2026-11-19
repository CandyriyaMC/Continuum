package lol.candyriya.continuum.storage.sql;

import lol.candyriya.continuum.common.config.DatabaseType;
import lol.candyriya.continuum.common.config.MysqlPool;
import lol.candyriya.continuum.common.config.MysqlSettings;
import lol.candyriya.continuum.common.config.SerializationConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlPlayerStorageTest {

    private static MysqlSettings settings(String host, int port, String database) {
        return new MysqlSettings(host, port, database, "continuum", "secret",
                new MysqlPool(8, 8, Duration.ofSeconds(2), Duration.ofMinutes(25)));
    }

    @Test
    void mysqlUrlUsesTheMysqlSchemeAndCrossEngineParams() {
        String url = SqlPlayerStorage.jdbcUrl(settings("db.internal", 3306, "continuum"), DatabaseType.MYSQL);
        assertTrue(url.startsWith("jdbc:mysql://db.internal:3306/continuum?"), url);
        assertTrue(url.contains("cachePrepStmts=true"), url);
        assertTrue(url.contains("prepStmtCacheSize=250"), url);
        assertTrue(url.contains("connectionCollation=utf8mb4_unicode_ci"), url);
        assertTrue(url.contains("tcpKeepAlive=true"), url);
        // properties that do not exist in both drivers must stay out
        assertFalse(url.contains("useUnicode"), url);
        assertFalse(url.contains("characterEncoding"), url);
    }

    @Test
    void mariadbUrlUsesTheMariadbScheme() {
        String url = SqlPlayerStorage.jdbcUrl(settings("db.internal", 3307, "continuum"), DatabaseType.MARIADB);
        assertTrue(url.startsWith("jdbc:mariadb://db.internal:3307/continuum?"), url);
        assertTrue(url.contains("connectionCollation=utf8mb4_unicode_ci"), url);
    }

    @Test
    void hostWithUrlUnsafeCharactersIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("db/internal", 3306, "continuum"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("db?internal", 3306, "continuum"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("db internal", 3306, "continuum"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings(" ", 3306, "continuum"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings(null, 3306, "continuum"), DatabaseType.MYSQL));
    }

    @Test
    void databaseMustBeABareCatalogName() {
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 3306, "bad name"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 3306, "bad?name"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 3306, ""), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 3306, "a".repeat(65)), DatabaseType.MYSQL));
        SqlPlayerStorage.jdbcUrl(settings("h", 3306, "a".repeat(64)), DatabaseType.MYSQL); // boundary is fine
    }

    @Test
    void portOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 0, "continuum"), DatabaseType.MYSQL));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.jdbcUrl(settings("h", 65_536, "continuum"), DatabaseType.MYSQL));
    }

    @Test
    void mongodbEngineIsRejectedBeforeAnyConnection() {
        // must fail fast with IllegalArgumentException, not attempt a JDBC connection
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.open(
                settings("h", 27017, "continuum"),
                DatabaseType.MONGODB,
                new SerializationConfig(false, 1024 * 1024),
                lol.candyriya.continuum.storage.api.StorageConfig.defaults()));
    }

    @Test
    void absurdConnectionTimeoutIsRejectedBeforeAnyConnection() {
        MysqlSettings tooFast = new MysqlSettings("h", 3306, "continuum", "u", "p",
                new MysqlPool(8, 8, Duration.ofMillis(100), Duration.ofMinutes(25)));
        assertThrows(IllegalArgumentException.class, () -> SqlPlayerStorage.open(
                tooFast, DatabaseType.MYSQL, new SerializationConfig(false, 1024 * 1024),
                lol.candyriya.continuum.storage.api.StorageConfig.defaults()));
    }
}
