package lol.candyriya.continuum.storage.outbox;

import lol.candyriya.continuum.common.config.DatabaseType;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MariaDB 11 coverage for the outbox claimers and the orphan scan.
 * Skips cleanly when docker is unavailable - CI runs it, a dockerless
 * dev machine does not fail the build.
 *
 * <p>A pre-provisioned server can be supplied instead of docker via the
 * {@code continuum.test.mariadb.port} system property or the
 * {@code CONTINUUM_TEST_MARIADB_PORT} environment variable (host/user/password
 * via {@code continuum.test.mariadb.host} / {@code ...user} / {@code ...password},
 * defaulting to 127.0.0.1 / root / empty password, database {@code continuum}).
 * When set, docker is not required; a shared database is tolerated.</p>
 */
class MariadbOutboxClaimerIntegrationTest extends AbstractSqlOutboxClaimerIntegrationTest {

    private static final String EXTERNAL_PORT = System.getProperty("continuum.test.mariadb.port",
            System.getenv("CONTINUUM_TEST_MARIADB_PORT"));
    private static final String EXTERNAL_HOST = System.getProperty("continuum.test.mariadb.host",
            System.getenv().getOrDefault("CONTINUUM_TEST_MARIADB_HOST", "127.0.0.1"));
    private static final String EXTERNAL_DB = System.getProperty("continuum.test.mariadb.database",
            System.getenv().getOrDefault("CONTINUUM_TEST_MARIADB_DATABASE", "continuum"));
    private static final String EXTERNAL_USER = System.getProperty("continuum.test.mariadb.user",
            System.getenv().getOrDefault("CONTINUUM_TEST_MARIADB_USER", "root"));
    private static final String EXTERNAL_PASSWORD = System.getProperty("continuum.test.mariadb.password",
            System.getenv().getOrDefault("CONTINUUM_TEST_MARIADB_PASSWORD", ""));

    private static MariaDBContainer container;

    @BeforeAll
    static void startDatabase() {
        if (EXTERNAL_PORT != null && !EXTERNAL_PORT.isBlank()) {
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "docker is not available - skipping MariaDB integration tests");
        container = new MariaDBContainer(DockerImageName.parse("mariadb:11"))
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
        return DatabaseType.MARIADB;
    }

    @Override
    protected TestDatabase connection() {
        if (EXTERNAL_PORT != null && !EXTERNAL_PORT.isBlank()) {
            return new TestDatabase(EXTERNAL_HOST, Integer.parseInt(EXTERNAL_PORT),
                    EXTERNAL_DB, EXTERNAL_USER, EXTERNAL_PASSWORD);
        }
        return new TestDatabase(container.getHost(), container.getMappedPort(3306),
                container.getDatabaseName(), container.getUsername(), container.getPassword());
    }
}
