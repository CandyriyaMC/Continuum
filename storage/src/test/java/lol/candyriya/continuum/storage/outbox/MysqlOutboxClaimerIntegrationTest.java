package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.config.DatabaseType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4 coverage for the outbox claimers and the orphan scan.
 * Skips cleanly when docker is unavailable - CI runs it, a dockerless
 * dev machine does not fail the build.
 *
 * <p>A pre-provisioned server can be supplied instead of docker via the
 * {@code continuum.test.mysql.port} system property or the
 * {@code CONTINUUM_TEST_MYSQL_PORT} environment variable (host/user/password
 * via {@code continuum.test.mysql.host} / {@code ...user} / {@code ...password},
 * defaulting to 127.0.0.1 / root / empty password, database {@code continuum}):
 * {@code CONTINUUM_TEST_MYSQL_PORT=3306 ./gradlew :storage:test}. When set, docker
 * is not required; the suite runs against the given server and every test uses
 * fresh player UUIDs, so a shared database is tolerated.</p>
 */
class MysqlOutboxClaimerIntegrationTest extends AbstractSqlOutboxClaimerIntegrationTest {

    private static final String EXTERNAL_PORT = System.getProperty("continuum.test.mysql.port",
            System.getenv("CONTINUUM_TEST_MYSQL_PORT"));
    private static final String EXTERNAL_HOST = System.getProperty("continuum.test.mysql.host",
            System.getenv().getOrDefault("CONTINUUM_TEST_MYSQL_HOST", "127.0.0.1"));
    private static final String EXTERNAL_DB = System.getProperty("continuum.test.mysql.database",
            System.getenv().getOrDefault("CONTINUUM_TEST_MYSQL_DATABASE", "continuum"));
    private static final String EXTERNAL_USER = System.getProperty("continuum.test.mysql.user",
            System.getenv().getOrDefault("CONTINUUM_TEST_MYSQL_USER", "root"));
    private static final String EXTERNAL_PASSWORD = System.getProperty("continuum.test.mysql.password",
            System.getenv().getOrDefault("CONTINUUM_TEST_MYSQL_PASSWORD", ""));

    private static MySQLContainer container;

    @BeforeAll
    static void startDatabase() {
        if (EXTERNAL_PORT != null && !EXTERNAL_PORT.isBlank()) {
            // an external server was supplied; docker stays out of it
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MySQL integration tests");
        container = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                .withDatabaseName("continuum")
                .withUsername("continuum")
                .withPassword("continuum");
        container.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    protected DatabaseType engine() {
        return DatabaseType.MYSQL;
    }

    @Override
    protected TestDatabase connection() {
        if (EXTERNAL_PORT != null && !EXTERNAL_PORT.isBlank()) {
            return new TestDatabase(EXTERNAL_HOST, Integer.parseInt(EXTERNAL_PORT),
                    EXTERNAL_DB, EXTERNAL_USER, EXTERNAL_PASSWORD);
        }
        return new TestDatabase(container.getHost(), container.getMappedPort(MySQLContainer.MYSQL_PORT),
                container.getDatabaseName(), container.getUsername(), container.getPassword());
    }
}
